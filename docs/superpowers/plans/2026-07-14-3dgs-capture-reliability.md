# 3DGS Capture Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every completed outdoor env scan delivers splat-usable inputs (video + pose + depth + GPS + compass) to R2, degradation is loud (flight recorder + on-screen + scan index), and reconstruction runs unattended on the DGX.

**Architecture:** Fix the chunk-0 stale-writer regression in `ArSessionRecorder` first (ships alone as PR A). Then add a Swift→Kotlin capture-diagnostics drain (Swift NSLog lines do NOT reach the flight recorder today — the drain is the only bridge), a thermal degrade-instead-of-drop ladder, explicit compass in `GeoRefEvent`, a `captureHealth` manifest block flowing into new `scan_sessions` columns, a recorded `splat_skip_reason` on `reconstruction_jobs`, and a systemd timer for the recon worker (PR B).

**Tech Stack:** Swift/AVFoundation (iosApp), Kotlin Multiplatform + kotlinx.serialization (shared/composeApp), Ktor+JDBC (backend/), Drizzle SQL migrations (site/), Python/psycopg (scripts/reconstruction), systemd user units (DGX).

**Spec:** `docs/superpowers/specs/2026-07-14-3dgs-capture-reliability-design.md`

## Global Constraints

- iosApp has **NO Swift unit-test target** — Swift changes are verified by simulator build + Kotlin-side tests + flight-recorder field evidence. Do not invent XCTest steps.
- After ANY Kotlin edit, before a simulator xcodebuild: `rm -rf composeApp/build/xcode-frameworks` (stale-framework trap — sim silently runs old code otherwise).
- Kotlin comments must never contain `/*` sequences inside comment bodies (nested block comments eat the terminator).
- Migrations are hand-written `NNNN_snake_case.sql`, applied manually via psql, tracked with a `drizzle.__drizzle_migrations` row. Next free number: **0039**. Never run drizzle-kit generate.
- `zsh`: piping a build through `tail`/`grep` masks the exit code — run gates bare or capture `$pipestatus[1]`.
- Manifest DTO changes must keep old JSON decodable (kotlinx `ignoreUnknownKeys` on `GccJson`; new fields must have defaults).
- PR A = Tasks 1–2 on branch `fix/env-scan-chunk0-video-writer` (ship first). PR B = Tasks 3–9 on branch `feat/3dgs-capture-reliability` branched from PR A's tip.
- Gates (run from repo root unless noted): `./gradlew :composeApp:compileKotlinIosSimulatorArm64`, `./gradlew :composeApp:desktopTest`, `./gradlew :backend:build`, `cd site && npx tsc --noEmit` (tsc from repo root is vacuous), `cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build`.

---

### Task 1: Writer lifecycle fix (the chunk-0 regression) — PR A

**Files:**
- Modify: `iosApp/iosApp/ArSessionRecorder.swift` (start() ~line 122, startChunk ~line 316, appendVideo ~line 381)

**Interfaces:**
- Produces: `private func resetWriterStack(context: String)` (serial-queue-only), `static func writerIsDead(_ status: AVAssetWriter.Status) -> Bool`, `private func noteWriterError()` counter hook (Task 5 reads `writerErrorCount`). Task 2 replaces the NSLog-only calls here with `enqueueDiagnostic`.

**Background for the implementer:** `start()` resets `chunkIndex/chunkActive` but never `writer/writerInput/adaptor/videoURL`. A stale `AVAssetWriter` (left when the async `finishSilently` finalize never ran before teardown/suspension) makes `appendVideo` skip `createWriter`; a non-`.writing` writer then silently drops every frame (`isReadyForMoreMediaData` false forever; `adaptor.append` false without `.failed`) until the ~300 s chunk rotation nils it. Field case: session `env-1783970967094-3wyhss` chunk-0. All writer state is confined to the private serial `queue`.

- [ ] **Step 1: Add the pure helper + reset + error counter**

In `ArSessionRecorder.swift`, below `createWriter` (after line ~452), add:

```swift
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
        noteWriterError()
        if let writer, writer.status == .writing { writer.cancelWriting() }
        writer = nil
        writerInput = nil
        adaptor = nil
        videoURL = nil
    }

    /// Counter seam: Task 5 (captureHealth) accumulates this into the
    /// manifest; until then it only exists so call sites don't change later.
    private var writerErrorCount = 0
    private func noteWriterError() { writerErrorCount += 1 }
```

(Swift allows the stored property inside the class body wherever placed — move `private var writerErrorCount = 0` up next to the other serial-queue properties at lines ~98–113 to follow the file's grouping convention, and keep only the funcs here.)

- [ ] **Step 2: Enforce the invariant at both lifecycle edges**

In `start(sessionId:outputDirPath:)`, inside the `queue.async` block, immediately after the `alreadyLive` early-return and before `self.sessionId = sessionId`, add:

```swift
            resetWriterStack(context: "session start")
```

In `startChunk(at:sourceWidth:sourceHeight:)`, as the FIRST line of the function body (before `guard let outputDir`):

```swift
        resetWriterStack(context: "chunk \(chunkIndex) start")
```

- [ ] **Step 3: Dead-writer detection in appendVideo**

In `appendVideo`, replace:

```swift
        if writer == nil {
            createWriter(sourceWidth: sourceWidth, sourceHeight: sourceHeight, firstTime: time)
        }
```

with:

```swift
        if let existing = writer, Self.writerIsDead(existing.status) {
            if existing.status == .failed {
                failCapture("writer failed: \(String(describing: existing.error))")
                return
            }
            // .completed/.cancelled writer left behind: recreate mid-chunk
            // instead of silently dropping every remaining frame.
            NSLog("GCC-DIAG ERR [capture] dead video writer (status=%d) — recreating",
                  existing.status.rawValue)
            noteWriterError()
            writer = nil
            writerInput = nil
            adaptor = nil
        }
        if writer == nil {
            createWriter(sourceWidth: sourceWidth, sourceHeight: sourceHeight, firstTime: time)
        }
```

Note: do NOT call `resetWriterStack` here — `videoURL` must survive (the current chunk still owns its .mov path). The existing `if writer.status == .failed { failCapture... }` check three lines below becomes redundant for the just-created writer but is harmless; leave it.

- [ ] **Step 4: Build gate**

```bash
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build
```
Expected: `BUILD SUCCEEDED`. (No Kotlin edits in this task, so no xcode-frameworks purge needed.)

- [ ] **Step 5: Commit**

```bash
git add iosApp/iosApp/ArSessionRecorder.swift
git commit -m "fix(capture): stale AVAssetWriter dropped chunk-0 video silently

start()/startChunk() now hard-reset the writer stack, and appendVideo
recreates a dead (.completed/.cancelled) writer instead of dropping every
frame until rotation. Field case: env-1783970967094-3wyhss.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Swift→Kotlin capture diagnostics drain — PR A

**Files:**
- Modify: `iosApp/iosApp/ArSessionRecorder.swift` (diag queue), `iosApp/iosApp/GccArNative.swift` (surface API ~line 598)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArNativeSurface.kt` (interface + parser)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` (tick drain ~line 779), `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (tick drain ~line 227)

**Interfaces:**
- Consumes: Task 1's `resetWriterStack`/dead-writer sites (their NSLog calls each gain an `enqueueDiagnostic` twin).
- Produces: Swift `func drainDiagnostics() -> [String]` on the recorder; `func drainCaptureDiagnostics() -> [String]` on `RealityKitArSurface`; Kotlin `ArNativeSurface.drainCaptureDiagnostics(): List<String>` (default `emptyList()`); packet format `"LEVEL|tag|message"` (LEVEL ∈ INFO/ERROR). Tasks 3/5 call `enqueueDiagnostic` for thermal/degrade events.

**Background:** Swift `NSLog("GCC-DIAG …")` goes only to the device console. events.jsonl entries originate exclusively in Kotlin: `DiagnosticsLog.append` → `sessionSink` → `SessionEventLog.diag(level, tag, message)` → JSONL. The recorder therefore queues packet strings that Kotlin drains on the existing per-tick chunk-drain loop and forwards into `DiagnosticsLog` — from there they flow to the event log and the backend's `error_count` metric automatically.

- [ ] **Step 1: Recorder-side queue (Swift)**

In `ArSessionRecorder.swift`, next to `finishedLock` (~line 88):

```swift
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
```

Then pair every Task-1 NSLog with an event: in `resetWriterStack` after the NSLog add
`enqueueDiagnostic(level: "ERROR", tag: "capture", message: "stale video writer at \(context) — cancelled")`;
in the appendVideo dead-writer branch add
`enqueueDiagnostic(level: "ERROR", tag: "capture", message: "dead video writer (status=\(existing.status.rawValue)) — recreated mid-chunk")`;
in `failCapture(_:)` (line ~583) after its NSLog add
`enqueueDiagnostic(level: "ERROR", tag: "capture", message: reason)`.

- [ ] **Step 2: Surface bridge (Swift)**

In `GccArNative.swift`, next to `drainFinishedCaptureChunks()` (~line 605):

```swift
    func drainCaptureDiagnostics() -> [String] {
        recorder?.drainDiagnostics() ?? []
    }
```

- [ ] **Step 3: Kotlin interface + forwarding**

In `ArNativeSurface.kt`, add to the `ArNativeSurface` interface (with a default so other implementors don't break):

```kotlin
    /** Capture-layer diagnostics queued Swift-side ("LEVEL|tag|message");
     *  drained per tick and forwarded into DiagnosticsLog so they reach the
     *  session flight recorder. Default: surfaces without a recorder. */
    fun drainCaptureDiagnostics(): List<String> = emptyList()
```

Below `parseCaptureChunkPacket` (~line 219) add the forwarder both overlays share:

```kotlin
internal fun forwardCaptureDiagnostics(packets: List<String>) {
    for (packet in packets) {
        val parts = packet.split('|', limit = 3)
        if (parts.size != 3) continue
        if (parts[0] == "ERROR") DiagnosticsLog.error(parts[1], parts[2])
        else DiagnosticsLog.info(parts[1], parts[2])
    }
}
```

(Import `com.gcc.app.platform.DiagnosticsLog` if not already in scope in that file.)

- [ ] **Step 4: Drain on both tick loops**

`ArEnvScan.kt` (ios actual), in the per-tick loop next to the existing `native.drainFinishedCaptureChunks()` (~line 779):

```kotlin
            forwardCaptureDiagnostics(native.drainCaptureDiagnostics())
```

`ArRouteOverlay.kt`, same addition next to its tick drain (~line 227).

- [ ] **Step 5: Gates**

```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64 :composeApp:desktopTest
rm -rf composeApp/build/xcode-frameworks
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build
```
Expected: all pass / BUILD SUCCEEDED.

- [ ] **Step 6: Commit, push, open PR A**

```bash
git add iosApp/iosApp/ArSessionRecorder.swift iosApp/iosApp/GccArNative.swift \
  composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArNativeSurface.kt \
  composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt \
  composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt
git commit -m "feat(capture): bridge recorder diagnostics into the flight recorder

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git push -u origin fix/env-scan-chunk0-video-writer
gh pr create --draft --title "fix(capture): chunk-0 video writer regression + capture diagnostics bridge" \
  --body "Tasks 1-2 of docs/superpowers/plans/2026-07-14-3dgs-capture-reliability.md. Ships first per Blake."
```

---

### Task 3: Thermal ladder — degrade, then warn — PR B

**Files:**
- Modify: `iosApp/iosApp/ArSessionRecorder.swift` (constants ~57–75, append ~211, processSample ~275, startChunk ~316, createWriter ~412)
- Modify: `iosApp/iosApp/GccArNative.swift`, `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArNativeSurface.kt`, `ArEnvScan.kt` (overlay state), `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt` (chip)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` (expect overlay: degraded-video StateFlow) + android/desktop actuals

**Interfaces:**
- Consumes: Task 2's `enqueueDiagnostic`.
- Produces: Swift `enum VideoTier: Int { case full = 0; case reduced = 1 }`, `static func tier(for: ProcessInfo.ThermalState) -> VideoTier`, surface `func captureVideoTierRaw() -> Int32` (−1 = no recorder); Kotlin `ArEnvScanOverlay.captureVideoDegraded: StateFlow<Boolean>`. Task 5 reads `maxThermalRaw`/`degradedChunkCount`.

- [ ] **Step 1: Tier machinery (Swift)**

Constants block gains:

```swift
    /// Reduced tier (thermal .serious): half the long edge, ~1.2 Mbps.
    private static let reducedLongEdge = 640
    private static let reducedBitRate = 1_200_000
```

Add near the top of the class:

```swift
    enum VideoTier: Int { case full = 0, reduced = 1 }
    static func tier(for thermal: ProcessInfo.ThermalState) -> VideoTier {
        thermal == .serious ? .reduced : .full
    }
    // Serial-queue confined:
    private var currentChunkTier: VideoTier = .full
    // stateLock confined (read by captureVideoTierRaw from any thread):
    private var lastAppendTierRaw: Int = VideoTier.full.rawValue
    private var lastThermalRaw: Int = ProcessInfo.ThermalState.nominal.rawValue
```

- [ ] **Step 2: append() — replace skipHeavy with the tier**

In `append(frame:)` replace `let skipHeavy = thermal == .serious // sidecar-only under thermal pressure` and the two `skipHeavy` uses with:

```swift
        let tier = Self.tier(for: thermal)
        // Thermal transitions are flight-recorder events (spec §1).
        stateLock.lock()
        if thermal.rawValue != lastThermalRaw {
            lastThermalRaw = thermal.rawValue
            stateLock.unlock()
            enqueueDiagnostic(level: "INFO", tag: "thermal",
                message: "thermal state → \(thermal.rawValue) (videoTier=\(tier.rawValue))")
        } else {
            stateLock.unlock()
        }
        // Video AND depth continue at .serious now — reduced tier instead of
        // the old skipHeavy drop (spec: degrade, then warn).
        let video: CVPixelBuffer? = frame.capturedImage
        var depth: CVPixelBuffer?
        if time - lastDepthTime >= Self.depthIntervalSeconds,
           let depthMap = (frame.smoothedSceneDepth ?? frame.sceneDepth)?.depthMap {
            depth = depthMap
            lastDepthTime = time
        }
```

and pass `tierRaw: tier.rawValue` through `processSample` (add the parameter). Also set `stateLock`-guarded `lastAppendTierRaw = tier.rawValue` in the same locked section.

- [ ] **Step 3: Rotate on tier change; tier-aware writer**

In `processSample`, extend the rotation condition:

```swift
        let tier = VideoTier(rawValue: tierRaw) ?? .full
        if chunkActive,
           time - chunkStartTime >= Self.chunkMaxSeconds || tier != currentChunkTier {
            finalizeCurrentChunk()
        }
```

`startChunk` gains a `tier: VideoTier` parameter: sets `currentChunkTier = tier`, and the sidecar header JSON adds `"videoTier":\(tier.rawValue),` after `"chunk":\(chunkIndex),`. `createWriter` gains `tier: VideoTier` and uses:

```swift
        let longEdge = tier == .reduced ? Self.reducedLongEdge : Self.targetLongEdge
        let bitRate = tier == .reduced ? Self.reducedBitRate : Self.averageBitRate
```

(`outputDimensions` gains a `targetLongEdge: Int` parameter — update its two call sites.) On starting a reduced chunk, `startChunk` also calls `enqueueDiagnostic(level: "INFO", tag: "capture", message: "chunk \(chunkIndex) at reduced video tier (thermal)")`.

- [ ] **Step 4: Surface + overlay + chip**

Swift surface: `func captureVideoTierRaw() -> Int32 { recorder?.currentTierRaw() ?? -1 }`; recorder adds `func currentTierRaw() -> Int32` reading `lastAppendTierRaw` under `stateLock`. Kotlin `ArNativeSurface`: `fun captureVideoTierRaw(): Int = -1` default. `ArEnvScanOverlay` (commonMain expect + all 3 actuals): `val captureVideoDegraded: StateFlow<Boolean>`; the ios actual updates it on the tick loop: `_captureVideoDegraded.value = (native.captureVideoTierRaw() == 1)`. `SpotScreen.kt`: collect it and, when true, render a chip (same visual pattern/row as `ScanSessionStatsChip`) with text `"Phone hot — 3D capture reduced"`.

- [ ] **Step 5: Gates + commit**

```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64 :composeApp:desktopTest
rm -rf composeApp/build/xcode-frameworks
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build
cd .. && git add -A iosApp composeApp && git commit -m "feat(capture): thermal degrade-then-warn video ladder

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Explicit compass in GeoRefEvents — PR B

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/EnvScanSession.kt` (GeoRefEvent, lines 44–59)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/LocationProvider.kt` (expect) + ios/android/desktop actuals (headingAccuracy flow)
- Modify: `updateGeoReference` expect (`commonMain/.../platform/ArEnvScan.kt:105`) + all 6 actuals (env+route × ios/android/desktop); callers `SpotScreen.kt:433`, `ArRouteScreen.kt:229`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/model/GeoRefEventHeadingTest.kt` (create)

**Interfaces:**
- Produces: `GeoRefEvent.headingDegrees: Double? = null`, `GeoRefEvent.headingAccuracyDegrees: Double? = null`; `updateGeoReference(lat, lng, accuracyMeters, headingDegrees: Double? = null, headingAccuracyDegrees: Double? = null)`; `LocationProvider.headingAccuracyDegrees: StateFlow<Float?>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gcc.app.model

import com.gcc.shared.model.GccJson
import com.gcc.shared.model.GeoRefEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeoRefEventHeadingTest {
    @Test
    fun headingRoundTrips() {
        val event = GeoRefEvent(
            atMs = 1L, lat = 41.85, lng = -87.70, accuracyMeters = 5.0,
            arX = 1f, arY = 2f, arZ = 3f, arTimestampSeconds = 10.5,
            headingDegrees = 271.5, headingAccuracyDegrees = 15.0,
        )
        val decoded = GccJson.decodeFromString(
            GeoRefEvent.serializer(), GccJson.encodeToString(GeoRefEvent.serializer(), event))
        assertEquals(271.5, decoded.headingDegrees)
        assertEquals(15.0, decoded.headingAccuracyDegrees)
    }

    @Test
    fun legacyEventWithoutHeadingStillDecodes() {
        val decoded = GccJson.decodeFromString(
            GeoRefEvent.serializer(),
            """{"atMs":1,"lat":41.0,"lng":-87.0,"arX":0.0,"arY":0.0,"arZ":0.0}""")
        assertNull(decoded.headingDegrees)
        assertNull(decoded.headingAccuracyDegrees)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew :composeApp:desktopTest --tests '*GeoRefEventHeadingTest*'` → FAIL (no such parameters).

- [ ] **Step 3: Implement** — add both fields (`val headingDegrees: Double? = null`, `val headingAccuracyDegrees: Double? = null`) to `GeoRefEvent`; add `headingDegrees: Double? = null, headingAccuracyDegrees: Double? = null` params to the `updateGeoReference` expect + all 6 actuals; the two iOS actuals copy them into the `GeoRefEvent(...)` constructions (`ArEnvScan.kt:490-499`, `ArRouteOverlay.kt:383`). `LocationProvider` expect gains `val headingAccuracyDegrees: StateFlow<Float?>`; iOS actual publishes `CLHeading.headingAccuracy` where `headingDegrees` is published; android/desktop actuals return `MutableStateFlow(null)`. Callers: `SpotScreen.kt:433` passes `gpsHeading?.toDouble()` + collected accuracy; `ArRouteScreen.kt:229` passes `viewModel.headingDegrees.value?.toDouble()` + accuracy via the same plumbing the VM uses for heading.

- [ ] **Step 4: Gates** — `./gradlew :composeApp:desktopTest :composeApp:compileKotlinIosSimulatorArm64` → PASS.

- [ ] **Step 5: Commit** — `git add -A shared composeApp && git commit -m "feat(scan): explicit compass heading in GeoRefEvents\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 5: captureHealth block, recorder → manifest — PR B

**Files:**
- Modify: `iosApp/iosApp/ArSessionRecorder.swift` (counters), `iosApp/iosApp/GccArNative.swift` (+`captureHealthJson()`), `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArNativeSurface.kt`, iosMain `ArEnvScan.kt`/`ArRouteOverlay.kt` (stopCapture returns health)
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/EnvScanSession.kt` (CaptureHealth DTO + manifest field + schema 3)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt:709`, `RouteScanViewModel.kt:1340` (populate; `OrphanSessionRecovery.kt:222` passes null health, keeps schema logic unified)
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/model/EnvScanManifestCaptureHealthTest.kt` (create)

**Interfaces:**
- Consumes: Task 1's `writerErrorCount`, Task 3's `maxThermalRaw` (add `private var maxThermalRaw = 0`, updated in append()'s locked section) and `currentChunkTier`.
- Produces: shared `@Serializable data class CaptureHealth(val sessionSeconds: Double? = null, val videoCoveredSeconds: Double? = null, val maxThermalState: String? = null, val degradedChunkCount: Int? = null, val droppedVideoChunkCount: Int? = null, val writerErrorCount: Int? = null)`; `EnvScanManifest.captureHealth: CaptureHealth? = null`; `captureSchema` = **3** when chunks present; Swift `func captureHealthJson() -> String?`; Kotlin `ArNativeSurface.captureHealthJson(): String? = null`; overlay `stopCapture(): Pair<List<CaptureChunkInfo>, String?>` — keep the old name but change the return, updating both VM call sites.

- [ ] **Step 1: Failing test** — mirror `EnvScanManifestEventLogTest` style: `captureHealthRoundTrips()` (build a manifest with `captureHealth = CaptureHealth(sessionSeconds = 311.2, videoCoveredSeconds = 44.7, maxThermalState = "serious", degradedChunkCount = 1, droppedVideoChunkCount = 1, writerErrorCount = 0)`, encode/decode via GccJson, assert equality) + `legacyManifestWithoutCaptureHealthStillDecodes()` (hand-written JSON without the field → null). Run: `./gradlew :composeApp:desktopTest --tests '*CaptureHealthTest*'` → FAIL.

- [ ] **Step 2: Recorder counters (Swift, serial queue):** add `chunkFirstVideoTime: TimeInterval?`, `videoCoveredSeconds: Double = 0`, `degradedChunkCount = 0`, `droppedVideoChunkCount = 0`. `appendVideo` sets `chunkFirstVideoTime = chunkFirstVideoTime ?? time` on each successful append. `finalizeCurrentChunk` accumulates: `if let first = chunkFirstVideoTime, lastVideoTime > first { videoCoveredSeconds += lastVideoTime - first }`, `if videoSampleCount == 0 { droppedVideoChunkCount += 1 }`, `if currentChunkTier == .reduced { degradedChunkCount += 1 }`, resets `chunkFirstVideoTime = nil`. `start()` zeroes all of them. Add:

```swift
    /// JSON mirror of the manifest's CaptureHealth block (Kotlin fills
    /// sessionSeconds). Read after stopAndDrain — serial queue is idle then.
    func healthJson() -> String {
        let thermalName = ["nominal", "fair", "serious", "critical"]
        let maxThermal = thermalName[min(max(maxThermalRaw, 0), 3)]
        return "{\"videoCoveredSeconds\":\(videoCoveredSeconds)," +
               "\"maxThermalState\":\"\(maxThermal)\"," +
               "\"degradedChunkCount\":\(degradedChunkCount)," +
               "\"droppedVideoChunkCount\":\(droppedVideoChunkCount)," +
               "\"writerErrorCount\":\(writerErrorCount)}"
    }
```

Surface: `func captureHealthJson() -> String? { recorder?.healthJson() }`.

- [ ] **Step 3: Kotlin plumbing:** `ArNativeSurface.captureHealthJson(): String? = null`; ios overlay `stopCapture()` returns `Pair(chunks, surface.captureHealthJson())`; both VMs decode via `GccJson.decodeFromString(CaptureHealth.serializer(), json)` inside `runCatching`, then `.copy(sessionSeconds = (completedAtMs - startedAtMs) / 1000.0)`, and set `captureHealth` on the manifest. Change the three `captureSchema` sites (`SpotViewModel.kt:709`, `RouteScanViewModel.kt:1340`, `OrphanSessionRecovery.kt:222`) from `2` to `3`.

- [ ] **Step 4: Gates + commit** — desktopTest + iOS compile + sim build (purge xcode-frameworks first); `git commit -m "feat(scan): captureHealth block in the env-scan manifest (captureSchema 3)\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 6: Migration 0039 + indexer capture-health columns — PR B

**Files:**
- Create: `site/src/db/migrations/0039_add_capture_health.sql`
- Modify: `site/src/db/schema.ts` (scanSessions ~line 1794, reconstructionJobs)
- Modify: `backend/src/main/kotlin/com/gcc/backend/ScanIndexRepository.kt` (SessionRow line 9, UPSERT_SESSION lines 171–212, binds 59–91), `backend/src/main/kotlin/com/gcc/backend/ScanIndexer.kt` (~lines 90–113)

**Interfaces:**
- Consumes: Task 5's `EnvScanManifest.captureHealth`.
- Produces: `scan_sessions.video_coverage_pct real`, `max_thermal_state varchar(16)`, `dropped_video_chunks integer`; `reconstruction_jobs.splat_skip_reason varchar(32)` (written by Task 7).

- [ ] **Step 1: Migration file**

```sql
-- 0039: capture-health columns (spec 2026-07-14-3dgs-capture-reliability).
-- Hand-written; apply via psql; insert a drizzle.__drizzle_migrations row.
ALTER TABLE scan_sessions
  ADD COLUMN IF NOT EXISTS video_coverage_pct real,
  ADD COLUMN IF NOT EXISTS max_thermal_state varchar(16),
  ADD COLUMN IF NOT EXISTS dropped_video_chunks integer;
ALTER TABLE reconstruction_jobs
  ADD COLUMN IF NOT EXISTS splat_skip_reason varchar(32);
```

- [ ] **Step 2: schema.ts** — add `videoCoveragePct: real("video_coverage_pct")`, `maxThermalState: varchar("max_thermal_state", { length: 16 })`, `droppedVideoChunks: integer("dropped_video_chunks")` to `scanSessions`; `splatSkipReason: varchar("splat_skip_reason", { length: 32 })` to the reconstruction jobs table. Gate: `cd site && npx tsc --noEmit` → 0 errors.

- [ ] **Step 3: Indexer** — `SessionRow` gains `videoCoveragePct: Double?`, `maxThermalState: String?`, `droppedVideoChunks: Int?`. `ScanIndexer` computes from `manifest.captureHealth`: coverage = `if (sessionSeconds > 0) (videoCoveredSeconds / sessionSeconds * 100.0) else null` (both non-null required). `UPSERT_SESSION`: add the three columns to INSERT + `?` placeholders + `col = COALESCE(EXCLUDED.col, scan_sessions.col)` lines + positional `setNullable…` binds — keep bind order exactly matching the column list. Gate: `./gradlew :backend:build` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit** — `git add site backend && git commit -m "feat(index): capture-health columns in the scan index (migration 0039)\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

- [ ] **Step 5 (CHECKPOINT — needs Blake/prod approval): apply 0039 to prod** via psql with `DATABASE_URL_UNPOOLED` from the MAIN checkout's `site/.env.local`, then insert the `drizzle.__drizzle_migrations` tracking row. Do not run unprompted.

---

### Task 7: splat_skip_reason through the pipeline — PR B

**Files:**
- Modify: `scripts/reconstruction/gsplat_runner.py` (return contract, lines ~112–135), `scripts/reconstruction/splat_train.py` (same contract), `scripts/reconstruction/fuse_cell.py` (FusionResult ~line 78, splat block lines 527–555), `scripts/reconstruction/recon_worker.py` (complete_cell UPDATE lines 227–232)
- Test: `scripts/reconstruction/tests/test_fuse_cell.py` (extend), `scripts/reconstruction/tests/test_recon_worker.py` (SCHEMA + assertion)

**Interfaces:**
- Produces: `FusionResult.splat_skip_reason: str | None = None` (None ⇔ a splat asset was produced); trainer contract `train_gsplat_for_cell(...) -> tuple[bytes | None, str | None]` (reason ∈ `no-video-frames`, `too-few-frames`, `training-failed`); `reconstruction_jobs.splat_skip_reason` written in `complete_cell`. Reason `disabled` set by `fuse_cell` when `splat_wanted` is falsy.

- [ ] **Step 1: Failing tests** — in `test_fuse_cell.py` add a test that monkeypatches the trainer to return `(None, "training-failed")`, calls the splat block path (or a new pure helper `resolve_splat_outcome(splat_wanted, splat_bytes, trainer_reason)` if you extract one), asserts `result.splat_skip_reason == "training-failed"`; plus `splat_wanted=False` → `"disabled"`; plus produced-bytes → `None`. In `test_recon_worker.py` add `splat_skip_reason varchar(32)` to the inline SCHEMA and assert `complete_cell` writes it (skips locally without the scratch Postgres — runs on the DGX). Run: `cd scripts/reconstruction && python3 -m pytest tests/test_fuse_cell.py -v` → new tests FAIL.

- [ ] **Step 2: Implement** — trainers return `(bytes, None)` on success; `(None, "no-video-frames")` when zero posed frames, `(None, "too-few-frames")` when `0 < n < 8`, `(None, "training-failed")` for venv-missing/empty-points/rc≠0. `fuse_cell`: `result.splat_skip_reason = "disabled"` when not `splat_wanted`; else unpack the tuple, append the asset on bytes (reason None), else set the reason and keep today's print. `complete_cell`: the jobs-closing UPDATE gains `splat_skip_reason=%s` bound to `result.splat_skip_reason`.

- [ ] **Step 3: Gates** — `python3 -m pytest tests/ -v` (from `scripts/reconstruction`, `.venv`; DB tests may skip locally) → PASS/SKIP, no FAIL.

- [ ] **Step 4: Commit** — `git add scripts/reconstruction && git commit -m "feat(recon): record why a cell produced no splat\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 8: Recon worker as a systemd timer — PR B

**Files:**
- Modify: `scripts/reconstruction/recon_worker.py` (main(), lines 349–376), `scripts/reconstruction/README.md`
- Create: `ops/systemd/gcc-recon-worker.service`, `ops/systemd/gcc-recon-worker.timer`

**Interfaces:**
- Produces: `recon_worker.py --drain` (process claimable jobs until none remain, then exit 0 — the oneshot-timer mode; `--once`/`--loop` unchanged).

- [ ] **Step 1: `--drain` flag** — add to the mutually-exclusive group: `group.add_argument("--drain", action="store_true", help="process jobs until the queue is empty, then exit (systemd timer mode)")`; in the dispatch, loop the existing single-job function until it reports no job claimed. Gate: `python3 recon_worker.py --drain` without DATABASE_URL → exits with the existing "DATABASE_URL required" error (arg parsing works).

- [ ] **Step 2: Units** — mirror `ops/systemd/gcc-privacy-scrub.{service,timer}` exactly (same header-comment install instructions, `EnvironmentFile=/home/codex450/cdx/dev-projects/gcc/backend/deploy/.env`), with: `WorkingDirectory=/home/codex450/cdx/dev-projects/gcc/scripts/reconstruction`, `ExecStart=/home/codex450/cdx/dev-projects/gcc/scripts/reconstruction/.venv/bin/python -u recon_worker.py --drain`, `TimeoutStartSec=8h` (gsplat trains up to 3 h/job; a drain may do several), `MemoryMax=64G`; timer: `OnBootSec=5min`, `OnUnitActiveSec=10min`, `Persistent=true`. The `REQUIRE_SCRUBBED_VIDEO` defer gate (#176) already serializes scrub-before-recon.

- [ ] **Step 3: README** — replace the manual "run `--once`/`--loop` by hand" section with the timer install (symlink → daemon-reload → `enable --now gcc-recon-worker.timer`), keeping the manual commands as a debugging appendix.

- [ ] **Step 4: Commit** — `git add scripts/reconstruction ops/systemd && git commit -m "feat(ops): recon worker runs on a systemd timer (--drain mode)\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

- [ ] **Step 5 (CHECKPOINT — DGX deploy, after PR B merges):** on the DGX (`ssh codex450@100.67.196.13`): `cd ~/cdx/dev-projects/gcc && git pull`, symlink both units into `~/.config/systemd/user/`, `systemctl --user daemon-reload && systemctl --user enable --now gcc-recon-worker.timer`, verify `systemctl --user list-timers | grep recon` and journal on the next fire.

---

### Task 9: Whole-branch verification + PR B

- [ ] **Step 1: Full gates** — `./gradlew :composeApp:desktopTest :composeApp:compileKotlinIosSimulatorArm64 :backend:build`; `cd site && npx tsc --noEmit`; `rm -rf composeApp/build/xcode-frameworks` then sim xcodebuild; `cd scripts/reconstruction && python3 -m pytest tests/ -v`.
- [ ] **Step 2: Fresh-eyes review** against the spec's five components + §0; confirm no silent-drop path in the recorder lacks a diagnostic event.
- [ ] **Step 3: Push + draft PR B** (`feat/3dgs-capture-reliability`), body linking spec + plan, noting the two checkpoints (prod migration 0039; DGX timer install) and the field acceptance gate: one outdoor scan → splat + `world` registration with zero manual DGX intervention, `video_coverage_pct` visible in the scan index.

## Self-Review Notes

- Spec coverage: §0→Task 1, §1→Tasks 2–3, §2→Task 4, §3→Tasks 5–6, §4→Task 8, §5→Task 7, acceptance→Task 9. Spec's "Swift unit tests via injected thermal provider" is NOT implementable (no test target exists) — replaced by build gates + flight-recorder evidence; noted deliberately.
- Type consistency: packet `"LEVEL|tag|message"` (Tasks 2/3/5); `CaptureHealth` field names identical in Swift healthJson(), Kotlin DTO, and indexer extraction; `splat_skip_reason` values identical in trainers/fuse_cell/DB column.
