import ARKit
import AVFoundation
import Compression
import Foundation

/// Background reconstruction-grade recorder for the native AR surface
/// (Pillar 1). Fed one `ARFrame` per `session(_:didUpdate:)` callback by
/// `RealityKitArSurface`, it spools three artifact streams per rotating
/// chunk into the session's output directory:
///
///  - `chunk-NNN.mov`    — HEVC (H.264 fallback) video of `capturedImage`,
///                         long edge 1280, ~3 Mbps, source-timed from
///                         `frame.timestamp`;
///  - `chunk-NNN.jsonl`  — per-frame pose sidecar: one header line
///                         (schema/session/dims) then per-frame records of
///                         t, transform[16], intrinsics[9] (column-major),
///                         trackingState, thermalState;
///  - `chunk-NNN.depth`  — LiDAR depth keyframes at 2 Hz: LZFSE-compressed
///                         `depthMap` planes as length-prefixed binary
///                         records (native little-endian), absent/empty on
///                         non-LiDAR devices (empty file deleted at
///                         finalize).
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
/// Thermal policy (checked per append, no timers):
///  - `.serious`  → skip video+depth appends; the cheap sidecar continues.
///  - `.critical` → auto-pause capture entirely; auto-resumes once thermal
///                  drops below `.serious`. Distinct from the user pause
///                  (`setEnabled`), which never auto-resumes.
///
/// Failure policy: NO error ever escapes to the scan path. Any writer or
/// file error logs `GCC-DIAG ERR [capture] …`, discards the in-progress
/// chunk (already-finished chunks stay drainable) and disables capture for
/// the rest of the session.
final class ArSessionRecorder {

    // MARK: - Tunables

    private static let chunkMaxSeconds: TimeInterval = 60
    private static let chunkMaxBytes: Int64 = 64 * 1024 * 1024
    /// Output video long edge; the short edge preserves the source aspect
    /// (1280×960 for ARKit's 4:3 feed — a literal 1280×720 would scale
    /// non-uniformly and corrupt the intrinsics correspondence the sidecar
    /// exists to preserve).
    private static let targetLongEdge = 1280
    private static let averageBitRate = 3_000_000
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

    // MARK: - Finished-chunk packets (finishedLock)

    private let finishedLock = NSLock()
    private var finishedChunks: [String] = []

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
    private var videoURL: URL?
    private var sidecarURL: URL?
    private var depthURL: URL?
    private var writer: AVAssetWriter?
    private var writerInput: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var videoSampleCount = 0
    private var lastVideoTime: TimeInterval = -.greatestFiniteMagnitude
    private var sizeCheckCountdown = ArSessionRecorder.sizeCheckEvery

    /// Delegate-thread-only (append is always the ARSession delegate thread).
    private var lastDepthTime: TimeInterval = -.greatestFiniteMagnitude

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
            finishedLock.lock()
            finishedChunks.removeAll()
            finishedLock.unlock()
            stateLock.lock()
            failed = false
            userPaused = false
            thermalPaused = false
            capturing = true
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
        stateLock.lock()
        if !capturing || failed {
            stateLock.unlock()
            return
        }
        if thermal == .critical {
            thermalPaused = true
        } else if thermalPaused, thermal.rawValue < ProcessInfo.ThermalState.serious.rawValue {
            thermalPaused = false
        }
        if thermalPaused || userPaused {
            stateLock.unlock()
            return
        }
        if pendingSamples >= Self.maxPendingSamples {
            stateLock.unlock()
            return // writer busy: drop, never queue
        }
        pendingSamples += 1
        stateLock.unlock()

        let skipHeavy = thermal == .serious // sidecar-only under thermal pressure
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
        // Retain ONLY the pixel buffers (never the ARFrame) across the hop.
        let video: CVPixelBuffer? = skipHeavy ? nil : frame.capturedImage
        var depth: CVPixelBuffer?
        if !skipHeavy, time - lastDepthTime >= Self.depthIntervalSeconds,
           let depthMap = (frame.smoothedSceneDepth ?? frame.sceneDepth)?.depthMap {
            depth = depthMap
            lastDepthTime = time
        }

        queue.async { [self] in
            processSample(
                time: time,
                transform: transform,
                intrinsics: intrinsics,
                tracking: tracking,
                thermal: thermal.rawValue,
                sourceWidth: sourceWidth,
                sourceHeight: sourceHeight,
                video: video,
                depth: depth
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
        sourceWidth: Int,
        sourceHeight: Int,
        video: CVPixelBuffer?,
        depth: CVPixelBuffer?
    ) {
        stateLock.lock()
        let live = capturing && !failed
        stateLock.unlock()
        guard live else { return }

        if chunkActive, time - chunkStartTime >= Self.chunkMaxSeconds {
            finalizeCurrentChunk()
        }
        if !chunkActive {
            startChunk(at: time, sourceWidth: sourceWidth, sourceHeight: sourceHeight)
            guard chunkActive else { return } // startChunk failed → capture disabled
        }

        appendSidecarLine(
            time: time, transform: transform, intrinsics: intrinsics,
            tracking: tracking, thermal: thermal
        )
        if let video {
            appendVideo(video, time: time, sourceWidth: sourceWidth, sourceHeight: sourceHeight)
        }
        if let depth, chunkActive {
            appendDepth(depth, time: time)
        }
    }

    /// Open the chunk's sidecar + depth files and write the sidecar header.
    /// The AVAssetWriter is created lazily on the first VIDEO sample — a
    /// chunk recorded entirely under `.serious` thermal is sidecar-only and
    /// must not leave an empty .mov behind.
    private func startChunk(at time: TimeInterval, sourceWidth: Int, sourceHeight: Int) {
        guard let outputDir else { return }
        let base = String(format: "chunk-%03d", chunkIndex)
        let vURL = outputDir.appendingPathComponent(base + ".mov")
        let sURL = outputDir.appendingPathComponent(base + ".jsonl")
        let dURL = outputDir.appendingPathComponent(base + ".depth")
        let fm = FileManager.default
        fm.createFile(atPath: sURL.path, contents: nil)
        fm.createFile(atPath: dURL.path, contents: nil)
        do {
            sidecarHandle = try FileHandle(forWritingTo: sURL)
            depthHandle = try FileHandle(forWritingTo: dURL)
        } catch {
            failCapture("chunk \(chunkIndex) file open failed: \(error)")
            return
        }
        videoURL = vURL
        sidecarURL = sURL
        depthURL = dURL
        chunkStartTime = time
        videoSampleCount = 0
        lastVideoTime = -.greatestFiniteMagnitude
        sizeCheckCountdown = Self.sizeCheckEvery
        chunkActive = true
        let (videoW, videoH) = Self.outputDimensions(sourceWidth: sourceWidth, sourceHeight: sourceHeight)
        writeSidecar(
            "{\"schema\":1,\"sessionId\":\"\(sessionId)\",\"chunk\":\(chunkIndex)," +
            "\"sourceWidth\":\(sourceWidth),\"sourceHeight\":\(sourceHeight)," +
            "\"videoWidth\":\(videoW),\"videoHeight\":\(videoH)}"
        )
    }

    private func appendSidecarLine(
        time: TimeInterval,
        transform: simd_float4x4,
        intrinsics: simd_float3x3,
        tracking: Int,
        thermal: Int
    ) {
        let t = [transform.columns.0, transform.columns.1, transform.columns.2, transform.columns.3]
            .flatMap { [$0.x, $0.y, $0.z, $0.w] }
        let k = [intrinsics.columns.0, intrinsics.columns.1, intrinsics.columns.2]
            .flatMap { [$0.x, $0.y, $0.z] }
        writeSidecar(
            "{\"t\":\(time)," +
            "\"transform\":[\(t.map { String($0) }.joined(separator: ","))]," +
            "\"intrinsics\":[\(k.map { String($0) }.joined(separator: ","))]," +
            "\"tracking\":\(tracking),\"thermal\":\(thermal)}"
        )
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
        if writer == nil {
            createWriter(sourceWidth: sourceWidth, sourceHeight: sourceHeight, firstTime: time)
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

    private func createWriter(sourceWidth: Int, sourceHeight: Int, firstTime: TimeInterval) {
        guard let videoURL else { return }
        let (width, height) = Self.outputDimensions(sourceWidth: sourceWidth, sourceHeight: sourceHeight)
        let settings: [String: Any] = [
            AVVideoCodecKey: Self.hevcSupported ? AVVideoCodecType.hevc : AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: Self.averageBitRate,
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

    /// Long edge `targetLongEdge`, aspect preserved, even dimensions (encoder
    /// requirement). Never upscales a smaller source.
    static func outputDimensions(sourceWidth: Int, sourceHeight: Int) -> (Int, Int) {
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

    // MARK: - Chunk lifecycle (serial queue)

    /// Close the chunk: sidecar/depth packets emit immediately (files are
    /// complete); the video finalizes asynchronously via `finishWriting` and
    /// emits its packet from the completion — the next chunk can start
    /// writing while the previous one is still wrapping its moov atom.
    private func finalizeCurrentChunk() {
        guard chunkActive else { return }
        chunkActive = false
        let index = chunkIndex
        chunkIndex += 1

        try? sidecarHandle?.close()
        sidecarHandle = nil
        try? depthHandle?.close()
        depthHandle = nil
        if let url = sidecarURL {
            emitFinished(index: index, kind: "sidecar", url: url, deleteIfEmpty: false)
        }
        if let url = depthURL {
            // Empty on non-LiDAR devices — delete instead of shipping a stub.
            emitFinished(index: index, kind: "depth", url: url, deleteIfEmpty: true)
        }
        sidecarURL = nil
        depthURL = nil

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
                    NSLog("GCC-DIAG ERR [capture] chunk %d video finalize failed: %@",
                          index, String(describing: finishingWriter.error))
                    try? FileManager.default.removeItem(at: finishingURL)
                }
                finalizeGroup.leave()
            }
        } else {
            if finishingWriter.status == .writing { finishingWriter.cancelWriting() }
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
        if let writer, writer.status == .writing { writer.cancelWriting() }
        writer = nil
        writerInput = nil
        adaptor = nil
        let fm = FileManager.default
        for url in [videoURL, sidecarURL, depthURL] {
            if let url { try? fm.removeItem(at: url) }
        }
        videoURL = nil
        sidecarURL = nil
        depthURL = nil
        // Never reuse a discarded index — chunk numbering stays monotonic.
        chunkIndex += 1
    }

    /// Terminal failure: log, disable capture for the session, drop the
    /// (possibly inconsistent) in-progress chunk. Finished chunks remain
    /// drainable; the scan itself never notices.
    private func failCapture(_ reason: String) {
        NSLog("GCC-DIAG ERR [capture] %@ — capture disabled for this session", reason)
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
