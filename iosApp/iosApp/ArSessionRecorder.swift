import ARKit
import AVFoundation
import Compression
import Foundation

/// Background reconstruction-grade recorder for the native AR surface
/// (Pillar 1). Fed one `ARFrame` per `session(_:didUpdate:)` callback by
/// `RealityKitArSurface`, it spools four artifact streams per rotating
/// chunk into the session's output directory:
///
///  - `chunk-NNN.mov`    — HEVC (H.264 fallback) video of `capturedImage`,
///                         long edge 1280, ~3 Mbps, source-timed from
///                         `frame.timestamp`;
///  - `chunk-NNN.jsonl`  — per-frame pose sidecar: one header line
///                         (schema/session/dims) then per-frame records of
///                         t, transform[16], intrinsics[9] (column-major),
///                         trackingState, thermalState, and nullable camera
///                         exposure/ISO/white-balance/focus metadata;
///  - `chunk-NNN.depth`  — LiDAR depth keyframes at 2 Hz: LZFSE-compressed
///                         `depthMap` planes as length-prefixed binary
///                         records (native little-endian), absent/empty on
///                         non-LiDAR devices (empty file deleted at
///                         finalize).
///  - `chunk-NNN.confidence` — raw ARKit depth-confidence planes selected
///                         with the depth keyframes at the same 2 Hz
///                         timestamps; versioned, explicitly little-endian
///                         length-prefixed records, absent on non-LiDAR.
///
/// Chunks rotate at 60 s or 64 MB. Finished chunks are handed to Kotlin as
/// "index|kind|path|sizeBytes" packet strings via poll-drain (the
/// `classifySceneLabels` packet-string precedent) — no cross-language
/// callbacks.
///
/// Threading model (three lanes):
///  - `append(frame:)` runs on the ARSession delegate thread (main). It is
///    allocation-light: flag checks under `stateLock`, value-type snapshots
///    of transform/intrinsics, then one async hop to `queue`. When the
///    serial queue is backed up (`maxPendingSamples`) the frame is DROPPED,
///    never queued — the live scan must not feel the recorder.
///  - `queue` (dedicated serial, utility QoS) owns all file/writer state:
///    chunk lifecycle, sidecar/depth writes, video appends, finalization.
///  - Protocol calls from Kotlin (start/drain/discard/stop/setEnabled)
///    arrive on arbitrary threads; they touch only locked flags or hop onto
///    `queue`. `stopCaptureAndDrain` is the one blocking call (queue.sync +
///    bounded wait for writer finalization) — it runs on the Kotlin tick /
///    scan-stop path, never inside the delegate callback.
///
/// Thermal policy (checked per append, no timers — spec §1 "degrade, then
/// warn"):
///  - `.serious`  → video AND depth continue, but the video encodes at the
///                  reduced tier (see `VideoTier`): half the long edge,
///                  ~1.2 Mbps instead of dropping to sidecar-only.
///  - `.critical` → auto-pause capture entirely; auto-resumes once thermal
///                  drops below `.serious`. Distinct from the user pause
///                  (`setEnabled`), which never auto-resumes.
///
/// Failure policy: NO error ever escapes to the scan path. Any writer or
/// file error logs `GCC-DIAG ERR [capture] …`, discards the in-progress
/// chunk (already-finished chunks stay drainable) and disables capture for
/// the rest of the session.
final class ArSessionRecorder {

    // MARK: - Thermal video tier (degrade, then warn — spec §1)

    enum VideoTier: Int { case full = 0, reduced = 1 }
    static func tier(for thermal: ProcessInfo.ThermalState) -> VideoTier {
        thermal == .serious ? .reduced : .full
    }

    // MARK: - Tunables

    private static let chunkMaxSeconds: TimeInterval = 60
    private static let chunkMaxBytes: Int64 = 64 * 1024 * 1024
    /// Output video long edge; the short edge preserves the source aspect
    /// (1280×960 for ARKit's 4:3 feed — a literal 1280×720 would scale
    /// non-uniformly and corrupt the intrinsics correspondence the sidecar
    /// exists to preserve).
    private static let targetLongEdge = 1280
    private static let averageBitRate = 3_000_000
    /// Reduced tier (thermal .serious): half the long edge, ~1.2 Mbps.
    private static let reducedLongEdge = 640
    private static let reducedBitRate = 1_200_000
    private static let depthIntervalSeconds: TimeInterval = 0.5
    /// Serial-queue backlog cap: beyond this, frames are dropped at the
    /// delegate thread. Also bounds how many ARKit-pool pixel buffers the
    /// recorder can hold at once (the camera pool is small — retaining many
    /// starves the session).
    private static let maxPendingSamples = 3
    /// Stat the growing .mov every N video appends for the 64 MB backstop
    /// (~1 s of video at 30 fps; the 60 s rotation is the primary bound).
    private static let sizeCheckEvery = 30
    /// HEVC hardware encode support probe (nil assistant = unsupported).
    private static let hevcSupported = AVOutputSettingsAssistant(preset: .hevc1920x1080) != nil

    // MARK: - Cross-thread flags (stateLock)

    private let stateLock = NSLock()
    private var capturing = false
    private var failed = false
    private var userPaused = false
    private var thermalPaused = false
    private var pendingSamples = 0
    /// Video tier as of the most recent append(), read by
    /// `currentTierRaw()` from any thread (surface polling).
    private var lastAppendTierRaw: Int = VideoTier.full.rawValue
    /// Last-seen raw thermal state, used to detect transitions worth an
    /// enqueueDiagnostic (spec §1: thermal transitions are flight-recorder
    /// events).
    private var lastThermalRaw: Int = ProcessInfo.ThermalState.nominal.rawValue
    /// Highest raw thermal state observed this session (Task 5 reads this
    /// into the capture-health summary).
    private var maxThermalRaw = 0

    // MARK: - Finished-chunk packets (finishedLock)

    private let finishedLock = NSLock()
    private var finishedChunks: [String] = []

    // MARK: - Capture diagnostics for the Kotlin flight recorder (diagLock)
    // Swift NSLog never reaches events.jsonl; Kotlin drains these per tick.
    private let diagLock = NSLock()
    private var pendingDiagnostics: [String] = []

    /// Thread-safe from any lane. Packet: "LEVEL|tag|message" (pipes in the
    /// message are flattened). Bounded so a pathological loop can't grow it.
    func enqueueDiagnostic(level: String, tag: String, message: String) {
        let flat = message.replacingOccurrences(of: "|", with: "/")
        diagLock.lock()
        if pendingDiagnostics.count < 64 {
            pendingDiagnostics.append("\(level)|\(tag)|\(flat)")
        }
        diagLock.unlock()
    }

    func drainDiagnostics() -> [String] {
        diagLock.lock()
        defer { diagLock.unlock() }
        let drained = pendingDiagnostics
        pendingDiagnostics.removeAll()
        return drained
    }

    // MARK: - Serial-queue-confined state

    private let queue = DispatchQueue(label: "gcc.capture.recorder", qos: .utility)
    /// Entered per `finishWriting`, left in its completion — `stopAndDrain`
    /// waits on this (bounded) so the video packet makes the final drain.
    private let finalizeGroup = DispatchGroup()

    private var sessionId = ""
    private var outputDir: URL?
    private var chunkIndex = 0
    private var chunkActive = false
    private var chunkStartTime: TimeInterval = 0
    private var sidecarHandle: FileHandle?
    private var depthHandle: FileHandle?
    private var confidenceHandle: FileHandle?
    private var videoURL: URL?
    private var sidecarURL: URL?
    private var depthURL: URL?
    private var confidenceURL: URL?
    private var writer: AVAssetWriter?
    private var writerInput: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var videoSampleCount = 0
    private var lastVideoTime: TimeInterval = -.greatestFiniteMagnitude
    private var sizeCheckCountdown = ArSessionRecorder.sizeCheckEvery
    private var writerErrorCount = 0
    /// Video tier the in-progress chunk was opened at (serial-queue
    /// confined) — a mid-chunk tier change rotates instead of switching
    /// encoders under an open writer.
    private var currentChunkTier: VideoTier = .full
    /// ARKit timestamp of the in-progress chunk's first successful video
    /// append (Task 5 captureHealth) — `finalizeCurrentChunk` subtracts this
    /// from `lastVideoTime` to accumulate `videoCoveredSeconds`. Nil outside
    /// an open chunk (and whenever no video has appended yet this chunk).
    private var chunkFirstVideoTime: TimeInterval?
    /// Total video-covered seconds across every finalized chunk this session
    /// (Task 5 captureHealth): sum of each chunk's last-minus-first
    /// successful video-append time.
    private var videoCoveredSeconds: Double = 0
    /// Chunks finalized at the thermal-reduced video tier this session
    /// (Task 5 captureHealth).
    private var degradedChunkCount = 0
    /// Chunks finalized with zero video samples this session — the writer
    /// never got a usable frame (Task 5 captureHealth).
    private var droppedVideoChunkCount = 0

    /// Delegate-thread-only (append is always the ARSession delegate thread).
    private var lastDepthTime: TimeInterval = -.greatestFiniteMagnitude
    /// ARKit world tracking uses the back wide camera in this capture
    /// surface. ARCamera exposes exact per-frame exposure duration, while
    /// these additional current sensor values are available only through
    /// AVCaptureDevice; nil keeps the JSON contract honest if unavailable.
    private let cameraCaptureDevice =
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)

    private struct CameraMetadata {
        let exposureDurationSeconds: Double?
        let iso: Double?
        let whiteBalanceRedGain: Double?
        let whiteBalanceGreenGain: Double?
        let whiteBalanceBlueGain: Double?
        let lensPosition: Double?
    }

    // MARK: - Kotlin-facing API (thread-safe)

    /// Begin capturing into `outputDirPath` (created if needed). Idempotent
    /// while live; a fresh call after stop starts over at chunk 0.
    func start(sessionId: String, outputDirPath: String) {
        queue.async { [self] in
            stateLock.lock()
            let alreadyLive = capturing
            stateLock.unlock()
            if alreadyLive {
                NSLog("GCC-DIAG [capture] start ignored — already capturing")
                return
            }
            resetWriterStack(context: "session start")
            let dir = URL(fileURLWithPath: outputDirPath, isDirectory: true)
            do {
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            } catch {
                NSLog("GCC-DIAG ERR [capture] output dir create failed: \(error) — capture disabled")
                return
            }
            self.sessionId = sessionId
            self.outputDir = dir
            self.chunkIndex = 0
            self.chunkActive = false
            // Session-scoped capture-health counters (Task 5): this recorder
            // instance is reused across capture sessions on the same
            // surface, so every high-water mark / accumulator must reset
            // here or a stale value leaks into the NEXT session's
            // captureHealth. Queue-confined counters reset directly (this
            // whole block already runs on `queue`, single-threaded).
            chunkFirstVideoTime = nil
            videoCoveredSeconds = 0
            degradedChunkCount = 0
            droppedVideoChunkCount = 0
            writerErrorCount = 0
            finishedLock.lock()
            finishedChunks.removeAll()
            finishedLock.unlock()
            stateLock.lock()
            failed = false
            userPaused = false
            thermalPaused = false
            capturing = true
            // Thermal/tier high-water marks are stateLock-confined (read
            // from other threads) — reset alongside the other stateLock
            // flags above, same reuse-across-sessions reasoning.
            maxThermalRaw = 0
            lastThermalRaw = ProcessInfo.ThermalState.nominal.rawValue
            lastAppendTierRaw = VideoTier.full.rawValue
            stateLock.unlock()
            NSLog("GCC-DIAG [capture] recording started session=%@", sessionId)
        }
    }

    /// Take (and clear) the finished-chunk packets: "index|kind|path|size".
    func drainFinishedChunks() -> [String] {
        finishedLock.lock()
        defer { finishedLock.unlock() }
        let drained = finishedChunks
        finishedChunks.removeAll()
        return drained
    }

    /// Thread-safe from any lane: the video tier as of the most recent
    /// append() (see [VideoTier]). Polled by the Kotlin overlay's tick loop
    /// to drive the "phone hot" REC chip.
    func currentTierRaw() -> Int32 {
        stateLock.lock()
        defer { stateLock.unlock() }
        return Int32(lastAppendTierRaw)
    }

    /// Privacy path (INDOOR flip): delete the in-progress chunk's files
    /// entirely; the next frame starts a fresh chunk index.
    func discardCurrentChunk() {
        queue.async { [self] in discardCurrentChunkOnQueue() }
    }

    /// Stop capturing, finalize the in-progress chunk, and return EVERY
    /// not-yet-drained packet. Blocks briefly (bounded 5 s) for the video
    /// finalize — callers are the Kotlin scan-stop path, never the ARSession
    /// delegate.
    func stopAndDrain() -> [String] {
        stateLock.lock()
        capturing = false
        stateLock.unlock()
        queue.sync { finalizeCurrentChunk() }
        if finalizeGroup.wait(timeout: .now() + 5) == .timedOut {
            NSLog("GCC-DIAG ERR [capture] video finalize timed out — last chunk may be missing")
        }
        return drainFinishedChunks()
    }

    /// JSON mirror of the manifest's CaptureHealth block (Kotlin fills
    /// sessionSeconds). Read after stopAndDrain — serial queue is idle then,
    /// so the queue-confined counters are safe to read without a queue hop.
    /// maxThermalRaw is stateLock-confined (updated from the delegate
    /// thread), not queue-confined, so it's read under stateLock here even
    /// though the rest of this method needs no lock.
    func healthJson() -> String {
        stateLock.lock()
        let rawThermal = maxThermalRaw
        stateLock.unlock()
        let thermalName = ["nominal", "fair", "serious", "critical"]
        let maxThermal = thermalName[min(max(rawThermal, 0), 3)]
        return "{\"videoCoveredSeconds\":\(videoCoveredSeconds)," +
               "\"maxThermalState\":\"\(maxThermal)\"," +
               "\"degradedChunkCount\":\(degradedChunkCount)," +
               "\"droppedVideoChunkCount\":\(droppedVideoChunkCount)," +
               "\"writerErrorCount\":\(writerErrorCount)}"
    }

    /// User pause/resume (scan-gate driven) without teardown. Distinct from
    /// the thermal auto-pause, which manages itself per append.
    func setEnabled(_ enabled: Bool) {
        stateLock.lock()
        userPaused = !enabled
        stateLock.unlock()
    }

    /// Teardown safety net: Kotlin stops capture BEFORE teardown, but if a
    /// teardown ever arrives with capture live, finalize asynchronously and
    /// drop the results silently rather than leak an open writer.
    func finishSilently() {
        stateLock.lock()
        let wasLive = capturing
        capturing = false
        stateLock.unlock()
        guard wasLive else { return }
        NSLog("GCC-DIAG [capture] teardown with live capture — finalizing silently")
        queue.async { [self] in finalizeCurrentChunk() }
    }

    // MARK: - Frame intake (ARSession delegate thread)

    /// One call per ARFrame. Allocation-light and never blocking: value-type
    /// snapshots + one async hop; drops the frame when the writer lane is
    /// busy. See the class doc for the thermal ladder applied here.
    func append(frame: ARFrame) {
        let thermal = ProcessInfo.processInfo.thermalState
        let tier = Self.tier(for: thermal)
        stateLock.lock()
        if !capturing || failed {
            stateLock.unlock()
            return
        }
        // CONSTRAINT: thermal-transition detection and the session high-water
        // mark run on EVERY call while capturing, before the pause/backlog
        // early returns below — the →critical transition and maxThermalRaw
        // must be recorded even on frames the critical pause drops (those are
        // exactly the episodes the flight recorder exists to explain, spec §1).
        if thermal.rawValue > maxThermalRaw { maxThermalRaw = thermal.rawValue }
        let thermalChanged = thermal.rawValue != lastThermalRaw
        if thermalChanged { lastThermalRaw = thermal.rawValue }
        if thermal == .critical {
            thermalPaused = true
        } else if thermalPaused, thermal.rawValue < ProcessInfo.ThermalState.serious.rawValue {
            thermalPaused = false
        }
        // paused → thermal/user pause; busy → writer backlog: drop, never queue.
        // lastAppendTierRaw deliberately updates only on frames that proceed:
        // it is "tier as of the most recent append", and .critical maps to
        // tier .full — updating it on paused frames would clear the "phone
        // hot" chip at the exact moment capture is thermal-paused.
        let dropFrame = thermalPaused || userPaused || pendingSamples >= Self.maxPendingSamples
        if !dropFrame {
            pendingSamples += 1
            lastAppendTierRaw = tier.rawValue
        }
        stateLock.unlock()
        // Thermal transitions are flight-recorder events (spec §1) — enqueued
        // outside stateLock (never nest it with diagLock).
        if thermalChanged {
            enqueueDiagnostic(level: "INFO", tag: "thermal",
                message: "thermal state → \(thermal.rawValue) (videoTier=\(tier.rawValue))")
        }
        if dropFrame { return }

        let time = frame.timestamp
        let camera = frame.camera
        let transform = camera.transform
        let intrinsics = camera.intrinsics
        let tracking: Int
        switch camera.trackingState {
        case .normal: tracking = 0
        case .limited: tracking = 1
        default: tracking = 2
        }
        let sourceWidth = CVPixelBufferGetWidth(frame.capturedImage)
        let sourceHeight = CVPixelBufferGetHeight(frame.capturedImage)
        let captureDevice = cameraCaptureDevice
        let whiteBalance = captureDevice?.deviceWhiteBalanceGains
        let cameraMetadata = CameraMetadata(
            exposureDurationSeconds: Self.finiteOrNil(camera.exposureDuration),
            iso: Self.finiteOrNil(captureDevice.map { Double($0.iso) }),
            whiteBalanceRedGain: Self.finiteOrNil(whiteBalance.map { Double($0.redGain) }),
            whiteBalanceGreenGain: Self.finiteOrNil(whiteBalance.map { Double($0.greenGain) }),
            whiteBalanceBlueGain: Self.finiteOrNil(whiteBalance.map { Double($0.blueGain) }),
            lensPosition: Self.finiteOrNil(captureDevice.map { Double($0.lensPosition) })
        )

        // Video AND depth continue at .serious now — reduced tier instead of
        // the old skipHeavy drop (spec: degrade, then warn).
        let video: CVPixelBuffer? = frame.capturedImage
        var depth: CVPixelBuffer?
        var confidence: CVPixelBuffer?
        if time - lastDepthTime >= Self.depthIntervalSeconds,
           let depthData = frame.smoothedSceneDepth ?? frame.sceneDepth {
            depth = depthData.depthMap
            confidence = depthData.confidenceMap
            lastDepthTime = time
        }

        // Retain ONLY the pixel buffers (never the ARFrame) across the hop.
        queue.async { [self] in
            processSample(
                time: time,
                transform: transform,
                intrinsics: intrinsics,
                tracking: tracking,
                thermal: thermal.rawValue,
                cameraMetadata: cameraMetadata,
                tierRaw: tier.rawValue,
                sourceWidth: sourceWidth,
                sourceHeight: sourceHeight,
                video: video,
                depth: depth,
                confidence: confidence
            )
            stateLock.lock()
            pendingSamples -= 1
            stateLock.unlock()
        }
    }

    // MARK: - Sample processing (serial queue)

    private func processSample(
        time: TimeInterval,
        transform: simd_float4x4,
        intrinsics: simd_float3x3,
        tracking: Int,
        thermal: Int,
        cameraMetadata: CameraMetadata,
        tierRaw: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        video: CVPixelBuffer?,
        depth: CVPixelBuffer?,
        confidence: CVPixelBuffer?
    ) {
        stateLock.lock()
        let live = capturing && !failed
        stateLock.unlock()
        guard live else { return }

        let tier = VideoTier(rawValue: tierRaw) ?? .full
        if chunkActive,
           time - chunkStartTime >= Self.chunkMaxSeconds || tier != currentChunkTier {
            finalizeCurrentChunk()
        }
        if !chunkActive {
            startChunk(at: time, tier: tier, sourceWidth: sourceWidth, sourceHeight: sourceHeight)
            guard chunkActive else { return } // startChunk failed → capture disabled
        }

        appendSidecarLine(
            time: time, transform: transform, intrinsics: intrinsics,
            tracking: tracking, thermal: thermal, cameraMetadata: cameraMetadata
        )
        if let video {
            appendVideo(video, time: time, sourceWidth: sourceWidth, sourceHeight: sourceHeight)
        }
        if let depth, chunkActive {
            appendDepth(depth, time: time)
        }
        if let confidence, chunkActive {
            appendConfidence(confidence, time: time)
        }
    }

    /// Open the chunk's sidecar + depth/confidence files and write the sidecar header.
    /// The AVAssetWriter is created lazily on the first VIDEO sample.
    /// [tier] is fixed for the chunk's lifetime — a thermal-driven tier
    /// change rotates to a fresh chunk (see `processSample`) rather than
    /// switching encoder settings under an open writer.
    private func startChunk(at time: TimeInterval, tier: VideoTier, sourceWidth: Int, sourceHeight: Int) {
        resetWriterStack(context: "chunk \(chunkIndex) start")
        guard let outputDir else { return }
        let base = String(format: "chunk-%03d", chunkIndex)
        let vURL = outputDir.appendingPathComponent(base + ".mov")
        let sURL = outputDir.appendingPathComponent(base + ".jsonl")
        let dURL = outputDir.appendingPathComponent(base + ".depth")
        let cURL = outputDir.appendingPathComponent(base + ".confidence")
        let fm = FileManager.default
        fm.createFile(atPath: sURL.path, contents: nil)
        fm.createFile(atPath: dURL.path, contents: nil)
        fm.createFile(atPath: cURL.path, contents: nil)
        do {
            sidecarHandle = try FileHandle(forWritingTo: sURL)
            depthHandle = try FileHandle(forWritingTo: dURL)
            confidenceHandle = try FileHandle(forWritingTo: cURL)
        } catch {
            try? sidecarHandle?.close()
            sidecarHandle = nil
            try? depthHandle?.close()
            depthHandle = nil
            try? confidenceHandle?.close()
            confidenceHandle = nil
            for url in [sURL, dURL, cURL] { try? fm.removeItem(at: url) }
            failCapture("chunk \(chunkIndex) file open failed: \(error)")
            return
        }
        videoURL = vURL
        sidecarURL = sURL
        depthURL = dURL
        confidenceURL = cURL
        chunkStartTime = time
        videoSampleCount = 0
        lastVideoTime = -.greatestFiniteMagnitude
        // Belt-and-suspenders alongside finalizeCurrentChunk's own reset:
        // discardCurrentChunkOnQueue (privacy purge) closes a chunk WITHOUT
        // going through finalizeCurrentChunk, so chunkFirstVideoTime must
        // also reset here or a stale value from the discarded chunk would
        // leak into this chunk's videoCoveredSeconds accumulation.
        chunkFirstVideoTime = nil
        sizeCheckCountdown = Self.sizeCheckEvery
        chunkActive = true
        currentChunkTier = tier
        let longEdge = tier == .reduced ? Self.reducedLongEdge : Self.targetLongEdge
        let (videoW, videoH) = Self.outputDimensions(
            sourceWidth: sourceWidth, sourceHeight: sourceHeight, targetLongEdge: longEdge
        )
        // Schema 2 (additive): epochMs + arT anchor BOTH clocks at chunk
        // open — per-frame `t` is ARKit's boot-relative clock, the manifest's
        // GPS path is epoch ms, and without this pair the DGX pipeline
        // cannot align poses with GPS (world-splat registration).
        writeSidecar(
            "{\"schema\":2,\"sessionId\":\"\(sessionId)\",\"chunk\":\(chunkIndex)," +
            "\"videoTier\":\(tier.rawValue)," +
            "\"sourceWidth\":\(sourceWidth),\"sourceHeight\":\(sourceHeight)," +
            "\"videoWidth\":\(videoW),\"videoHeight\":\(videoH)," +
            "\"epochMs\":\(Int64(Date().timeIntervalSince1970 * 1000)),\"arT\":\(time)}"
        )
        if tier == .reduced {
            enqueueDiagnostic(level: "INFO", tag: "capture",
                message: "chunk \(chunkIndex) at reduced video tier (thermal)")
        }
    }

    private func appendSidecarLine(
        time: TimeInterval,
        transform: simd_float4x4,
        intrinsics: simd_float3x3,
        tracking: Int,
        thermal: Int,
        cameraMetadata: CameraMetadata
    ) {
        let t = [transform.columns.0, transform.columns.1, transform.columns.2, transform.columns.3]
            .flatMap { [$0.x, $0.y, $0.z, $0.w] }
        let k = [intrinsics.columns.0, intrinsics.columns.1, intrinsics.columns.2]
            .flatMap { [$0.x, $0.y, $0.z] }
        writeSidecar(
            "{\"t\":\(time)," +
            "\"transform\":[\(t.map { String($0) }.joined(separator: ","))]," +
            "\"intrinsics\":[\(k.map { String($0) }.joined(separator: ","))]," +
            "\"tracking\":\(tracking),\"thermal\":\(thermal)," +
            "\"exposureDurationSeconds\":\(Self.jsonNumber(cameraMetadata.exposureDurationSeconds))," +
            "\"iso\":\(Self.jsonNumber(cameraMetadata.iso))," +
            "\"whiteBalanceRedGain\":\(Self.jsonNumber(cameraMetadata.whiteBalanceRedGain))," +
            "\"whiteBalanceGreenGain\":\(Self.jsonNumber(cameraMetadata.whiteBalanceGreenGain))," +
            "\"whiteBalanceBlueGain\":\(Self.jsonNumber(cameraMetadata.whiteBalanceBlueGain))," +
            "\"lensPosition\":\(Self.jsonNumber(cameraMetadata.lensPosition))}"
        )
    }

    private static func finiteOrNil(_ value: Double?) -> Double? {
        value?.isFinite == true ? value : nil
    }

    private static func jsonNumber(_ value: Double?) -> String {
        value.map { String($0) } ?? "null"
    }

    private func writeSidecar(_ line: String) {
        guard let handle = sidecarHandle, let data = (line + "\n").data(using: .utf8) else { return }
        do {
            try handle.write(contentsOf: data)
        } catch {
            failCapture("sidecar write failed: \(error)")
        }
    }

    private func appendVideo(_ buffer: CVPixelBuffer, time: TimeInterval, sourceWidth: Int, sourceHeight: Int) {
        if let existing = writer, Self.writerIsDead(existing.status) {
            if existing.status == .failed {
                failCapture("writer failed: \(String(describing: existing.error))")
                return
            }
            // .completed/.cancelled writer left behind: recreate mid-chunk
            // instead of silently dropping every remaining frame.
            NSLog("GCC-DIAG ERR [capture] dead video writer (status=%d) — recreating",
                  existing.status.rawValue)
            enqueueDiagnostic(level: "ERROR", tag: "capture",
                              message: "dead video writer (status=\(existing.status.rawValue)) — recreated mid-chunk")
            noteWriterError()
            writer = nil
            writerInput = nil
            adaptor = nil
        }
        if writer == nil {
            createWriter(tier: currentChunkTier, sourceWidth: sourceWidth, sourceHeight: sourceHeight, firstTime: time)
        }
        guard let writer, let input = writerInput, let adaptor, chunkActive else { return }
        if writer.status == .failed {
            failCapture("writer failed: \(String(describing: writer.error))")
            return
        }
        // Drop-not-queue: encoder busy, or out-of-order timestamp.
        guard input.isReadyForMoreMediaData, time > lastVideoTime else { return }
        let pts = CMTime(seconds: time, preferredTimescale: 1_000_000_000)
        guard adaptor.append(buffer, withPresentationTime: pts) else {
            if writer.status == .failed {
                failCapture("video append failed: \(String(describing: writer.error))")
            }
            return
        }
        lastVideoTime = time
        // First successful append of the in-progress chunk (Task 5
        // captureHealth) — finalizeCurrentChunk turns this into coverage
        // seconds. `??` so only the FIRST append of the chunk sets it.
        chunkFirstVideoTime = chunkFirstVideoTime ?? time
        videoSampleCount += 1
        sizeCheckCountdown -= 1
        if sizeCheckCountdown <= 0 {
            sizeCheckCountdown = Self.sizeCheckEvery
            if let path = videoURL?.path,
               let size = (try? FileManager.default.attributesOfItem(atPath: path))?[.size] as? Int64,
               size >= Self.chunkMaxBytes {
                finalizeCurrentChunk()
            }
        }
    }

    private func createWriter(tier: VideoTier, sourceWidth: Int, sourceHeight: Int, firstTime: TimeInterval) {
        guard let videoURL else { return }
        let longEdge = tier == .reduced ? Self.reducedLongEdge : Self.targetLongEdge
        let bitRate = tier == .reduced ? Self.reducedBitRate : Self.averageBitRate
        let (width, height) = Self.outputDimensions(
            sourceWidth: sourceWidth, sourceHeight: sourceHeight, targetLongEdge: longEdge
        )
        let settings: [String: Any] = [
            AVVideoCodecKey: Self.hevcSupported ? AVVideoCodecType.hevc : AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: bitRate,
                AVVideoExpectedSourceFrameRateKey: 60,
                AVVideoMaxKeyFrameIntervalKey: 120,
            ],
        ]
        do {
            let writer = try AVAssetWriter(outputURL: videoURL, fileType: .mov)
            let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
            input.expectsMediaDataInRealTime = true
            guard writer.canAdd(input) else {
                failCapture("writer rejected video input (\(width)x\(height))")
                return
            }
            writer.add(input)
            // nil source attributes: the adaptor accepts ARKit's native 420f
            // buffers and the input scales (uniformly — aspect preserved by
            // outputDimensions) to the output size.
            let adaptor = AVAssetWriterInputPixelBufferAdaptor(
                assetWriterInput: input, sourcePixelBufferAttributes: nil
            )
            guard writer.startWriting() else {
                failCapture("startWriting failed: \(String(describing: writer.error))")
                return
            }
            writer.startSession(atSourceTime: CMTime(seconds: firstTime, preferredTimescale: 1_000_000_000))
            self.writer = writer
            self.writerInput = input
            self.adaptor = adaptor
        } catch {
            failCapture("writer init failed: \(error)")
        }
    }

    /// A writer in a terminal state can never accept another sample —
    /// .completed/.cancelled/.failed writers must be discarded, not retried.
    /// (.unknown never occurs here: createWriter calls startWriting()
    /// immediately; kept in the dead set defensively.)
    static func writerIsDead(_ status: AVAssetWriter.Status) -> Bool {
        switch status {
        case .writing: return false
        default: return true
        }
    }

    /// Serial-queue only. Drop any surviving writer stack. A stale writer
    /// outside a chunk boundary is always a bug (the chunk-0 regression):
    /// log loudly so the flight recorder's diag tail carries the evidence.
    private func resetWriterStack(context: String) {
        guard writer != nil || writerInput != nil || adaptor != nil else { return }
        NSLog("GCC-DIAG ERR [capture] stale video writer at %@ — cancelled (status=%d)",
              context, writer?.status.rawValue ?? -1)
        enqueueDiagnostic(level: "ERROR", tag: "capture",
                          message: "stale video writer at \(context) — cancelled")
        noteWriterError()
        if let writer, writer.status == .writing { writer.cancelWriting() }
        writer = nil
        writerInput = nil
        adaptor = nil
        videoURL = nil
    }

    /// Accumulated into `healthJson()`'s `writerErrorCount` (Task 5
    /// captureHealth).
    private func noteWriterError() { writerErrorCount += 1 }

    /// Long edge [targetLongEdge] (the tier's — full or reduced), aspect
    /// preserved, even dimensions (encoder requirement). Never upscales a
    /// smaller source.
    static func outputDimensions(sourceWidth: Int, sourceHeight: Int, targetLongEdge: Int) -> (Int, Int) {
        guard sourceWidth > 0, sourceHeight > 0 else { return (targetLongEdge, targetLongEdge * 3 / 4) }
        let scale = min(1.0, Double(targetLongEdge) / Double(max(sourceWidth, sourceHeight)))
        let width = max(2, Int((Double(sourceWidth) * scale / 2).rounded()) * 2)
        let height = max(2, Int((Double(sourceHeight) * scale / 2).rounded()) * 2)
        return (width, height)
    }

    /// Depth keyframe record (all integers native little-endian):
    /// [UInt32 recordLength (excluding itself)] [Float64 t] [UInt32 width]
    /// [UInt32 height] [UInt32 bytesPerRow] [UInt32 pixelFormat]
    /// [UInt32 compressedLength] [LZFSE bytes…]
    private func appendDepth(_ buffer: CVPixelBuffer, time: TimeInterval) {
        guard let handle = depthHandle else { return }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return }
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
        let format = CVPixelBufferGetPixelFormatType(buffer)
        let sourceSize = height * bytesPerRow
        guard sourceSize > 0 else { return }
        let destination = UnsafeMutablePointer<UInt8>.allocate(capacity: sourceSize)
        defer { destination.deallocate() }
        let compressed = compression_encode_buffer(
            destination, sourceSize,
            base.assumingMemoryBound(to: UInt8.self), sourceSize,
            nil, COMPRESSION_LZFSE
        )
        // 0 = failed or incompressible past the raw size: skip this keyframe
        // (depth is best-effort; the capture itself continues).
        guard compressed > 0 else { return }
        var record = Data(capacity: 32 + compressed)
        func put<T>(_ value: T) {
            withUnsafeBytes(of: value) { record.append(contentsOf: $0) }
        }
        put(UInt32(28 + compressed))
        put(Double(time))
        put(UInt32(width))
        put(UInt32(height))
        put(UInt32(bytesPerRow))
        put(UInt32(format))
        put(UInt32(compressed))
        record.append(destination, count: compressed)
        do {
            try handle.write(contentsOf: record)
        } catch {
            failCapture("depth write failed: \(error)")
        }
    }

    /// Confidence record v1 (every integer and Float64 bit pattern is
    /// explicitly little-endian):
    /// [UInt32 recordLength excluding itself] [UInt16 version=1]
    /// [UInt16 fixedHeaderLength=32] [Float64 t] [UInt32 width]
    /// [UInt32 height] [UInt32 bytesPerRow] [UInt32 pixelFormat]
    /// [UInt32 payloadLength] [raw plane bytes including row padding]
    private func appendConfidence(_ buffer: CVPixelBuffer, time: TimeInterval) {
        guard let handle = confidenceHandle else { return }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return }
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
        let pixelFormat = CVPixelBufferGetPixelFormatType(buffer)
        let payloadLength = height * bytesPerRow
        guard payloadLength > 0, payloadLength <= Int(UInt32.max) - 32 else { return }

        var record = Data(capacity: 36 + payloadLength)
        func putInteger<T: FixedWidthInteger>(_ value: T) {
            var littleEndian = value.littleEndian
            withUnsafeBytes(of: &littleEndian) { record.append(contentsOf: $0) }
        }
        putInteger(UInt32(32 + payloadLength))
        putInteger(UInt16(1))
        putInteger(UInt16(32))
        putInteger(Double(time).bitPattern)
        putInteger(UInt32(width))
        putInteger(UInt32(height))
        putInteger(UInt32(bytesPerRow))
        putInteger(UInt32(pixelFormat))
        putInteger(UInt32(payloadLength))
        record.append(base.assumingMemoryBound(to: UInt8.self), count: payloadLength)
        do {
            try handle.write(contentsOf: record)
        } catch {
            failCapture("confidence write failed: \(error)")
        }
    }

    // MARK: - Chunk lifecycle (serial queue)

    /// Close the chunk: sidecar/depth/confidence packets emit immediately (files are
    /// complete); the video finalizes asynchronously via `finishWriting` and
    /// emits its packet from the completion — the next chunk can start
    /// writing while the previous one is still wrapping its moov atom.
    private func finalizeCurrentChunk() {
        guard chunkActive else { return }
        // Task 5 captureHealth: accumulate this chunk's coverage/degrade
        // counters BEFORE the lifecycle resets below touch chunkFirstVideoTime.
        if let first = chunkFirstVideoTime, lastVideoTime > first {
            videoCoveredSeconds += lastVideoTime - first
        }
        if videoSampleCount == 0 { droppedVideoChunkCount += 1 }
        if currentChunkTier == .reduced { degradedChunkCount += 1 }
        chunkFirstVideoTime = nil
        chunkActive = false
        let index = chunkIndex
        chunkIndex += 1

        try? sidecarHandle?.close()
        sidecarHandle = nil
        try? depthHandle?.close()
        depthHandle = nil
        try? confidenceHandle?.close()
        confidenceHandle = nil
        if let url = sidecarURL {
            emitFinished(index: index, kind: "sidecar", url: url, deleteIfEmpty: false)
        }
        if let url = depthURL {
            // Empty on non-LiDAR devices — delete instead of shipping a stub.
            emitFinished(index: index, kind: "depth", url: url, deleteIfEmpty: true)
        }
        if let url = confidenceURL {
            emitFinished(index: index, kind: "confidence", url: url, deleteIfEmpty: true)
        }
        sidecarURL = nil
        depthURL = nil
        confidenceURL = nil

        let finishingWriter = writer
        let finishingInput = writerInput
        let finishingURL = videoURL
        let sampleCount = videoSampleCount
        writer = nil
        writerInput = nil
        adaptor = nil
        videoURL = nil
        guard let finishingWriter, let finishingURL else { return }
        if sampleCount > 0, finishingWriter.status == .writing {
            finishingInput?.markAsFinished()
            finalizeGroup.enter()
            finishingWriter.finishWriting { [self] in
                if finishingWriter.status == .completed {
                    emitFinished(index: index, kind: "video", url: finishingURL, deleteIfEmpty: false)
                } else {
                    // Field session env-1784734348036 (build 159): the .mov
                    // silently never reached R2 while videoCoveredSeconds
                    // read 88% — NSLog alone is invisible to the flight
                    // recorder, so nothing in events.jsonl explained the
                    // missing chunk and reconstruction starved. Route the
                    // failure through the diagnostics bridge so it lands in
                    // the session event log.
                    enqueueDiagnostic(level: "ERROR", tag: "capture",
                                      message: "chunk \(index) video finalize failed: \(String(describing: finishingWriter.error))")
                    NSLog("GCC-DIAG ERR [capture] chunk %d video finalize failed: %@",
                          index, String(describing: finishingWriter.error))
                    try? FileManager.default.removeItem(at: finishingURL)
                }
                finalizeGroup.leave()
            }
        } else {
            if finishingWriter.status == .writing { finishingWriter.cancelWriting() }
            if sampleCount > 0 {
                // Writer left .writing before finalize (e.g. a mid-chunk
                // append error) with real frames on disk — same silent-video
                // -loss class as a failed finishWriting; make it visible.
                enqueueDiagnostic(level: "ERROR", tag: "capture",
                                  message: "chunk \(index) video discarded: writer status \(finishingWriter.status.rawValue) with \(sampleCount) samples (\(String(describing: finishingWriter.error)))")
            }
            try? FileManager.default.removeItem(at: finishingURL)
        }
    }

    private func discardCurrentChunkOnQueue() {
        guard chunkActive else { return }
        chunkActive = false
        try? sidecarHandle?.close()
        sidecarHandle = nil
        try? depthHandle?.close()
        depthHandle = nil
        try? confidenceHandle?.close()
        confidenceHandle = nil
        if let writer, writer.status == .writing { writer.cancelWriting() }
        writer = nil
        writerInput = nil
        adaptor = nil
        let fm = FileManager.default
        for url in [videoURL, sidecarURL, depthURL, confidenceURL] {
            if let url { try? fm.removeItem(at: url) }
        }
        videoURL = nil
        sidecarURL = nil
        depthURL = nil
        confidenceURL = nil
        // Never reuse a discarded index — chunk numbering stays monotonic.
        chunkIndex += 1
    }

    /// Terminal failure: log, disable capture for the session, drop the
    /// (possibly inconsistent) in-progress chunk. Finished chunks remain
    /// drainable; the scan itself never notices.
    private func failCapture(_ reason: String) {
        NSLog("GCC-DIAG ERR [capture] %@ — capture disabled for this session", reason)
        enqueueDiagnostic(level: "ERROR", tag: "capture", message: reason)
        stateLock.lock()
        failed = true
        stateLock.unlock()
        discardCurrentChunkOnQueue()
    }

    private func emitFinished(index: Int, kind: String, url: URL, deleteIfEmpty: Bool) {
        let size = ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int64) ?? 0
        if deleteIfEmpty, size <= 0 {
            try? FileManager.default.removeItem(at: url)
            return
        }
        finishedLock.lock()
        finishedChunks.append("\(index)|\(kind)|\(url.path)|\(size)")
        finishedLock.unlock()
    }
}
