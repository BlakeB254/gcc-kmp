# Pillar 1+6 — Full-Session AR Capture → R2 + Resilient Recorder: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Checkbox steps.

**Goal:** Every env scan records reconstruction-grade data in the background — HEVC video chunks + per-frame pose/intrinsics JSONL + LiDAR depth keyframes — spooled to disk, uploaded resumably to R2, surviving app-kill; plus the in-memory GPS-session recorder becomes disk-backed (Pillar 6).

**Spec:** docs/superpowers/specs/2026-07-06-world-scanning-v2-living-map-design.md (Pillars 1 & 6). Recon facts are in `.superpowers/sdd/progress.md` (Round-2 ledger) — implementers MUST read their task's anchors in the actual code first.

**Scope guard (v1):** ENV/SPOT scan only (the `ArEnvScanOverlay`/`RealityKitArSurface` path). Route-scan capture is a follow-up. SceneKit-fallback devices: no capture (native surface required). Android: no-op.

## Global Constraints

- Kotlin gates: `./gradlew :composeApp:desktopTest` + `:composeApp:linkDebugFrameworkIosSimulatorArm64`. Swift-touching tasks additionally: purge `composeApp/build/xcode-frameworks` + full `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build`. Server tasks: `cd site && npx tsc --noEmit`.
- **pbxproj hazard:** Task 4 adds a Swift file (4-line pbxproj pattern, template = CrashDiagnostics.swift entries, two fresh 24-hex UUIDs). NO fastlane build may run concurrently (fastlane mutates pbxproj). Controller sequences this.
- Presigned PUTs: token-free `siteClient` + `takeFromRawEncoded` ONLY (`GccApiClient.uploadToPresignedUrl` is the precedent — reuse it, never hand-roll).
- Big blobs on disk via FileManager (Documents), NEVER in multiplatform-settings. Bookkeeping in SQLDelight (pattern: `shared/src/commonMain/sqldelight/com/gcc/shared/cache/ScanAreaCache.sq` + `SqlDriverFactory`).
- Capture must NEVER degrade the live scan: recorder failures are logged (DiagnosticsLog) and disable capture for the session — no exception may escape into the scan path; no main-thread blocking work.
- Privacy: INDOOR flip discards the in-progress chunk entirely (≤60s granularity, conservative) and halts capture until OUTDOOR. Document this as the v1 truncation semantic (spec asked trailing-window truncation; chunk-discard over-purges toward privacy — deliberate).
- Wire-model changes: new fields MUST have defaults (old clients/serialized state must decode).
- `Coordinates(lng, lat)` GeoJSON order; named args always.

---

### Task 1: Server seam — session-artifact upload URLs

**Files:** `site/src/lib/r2.ts`, `site/src/app/api/captures/upload-url/route.ts`

- [ ] Add `rawSessionArtifactKey(locationCellId, sessionId, artifactPath)` to r2.ts → `raw/{locationCellId}/{sessionId}/{artifactPath}` where sessionId sanitized like capturedAt today, and artifactPath validated per-segment (each segment `[0-9A-Za-z._-]+`, joined by `/`, ≤5 segments, ≤200 chars total) — slashes PRESERVED between segments (the existing `rawScanKey` strips them; do not modify it).
- [ ] Route: request body gains optional `sessionId` + `artifactPath`; when both present use the new key builder (else legacy path, byte-identical behavior). Extend the fixed MIME map: `mov→video/quicktime`, `jsonl→application/x-ndjson` (bin/json already exist). Validate strictly; reject `..`.
- [ ] tsc clean. Commit: `feat(capture): session-artifact presigned upload keys`

### Task 2: Wire models + client

**Files:** `shared/.../model/EnvScanSession.kt`, `shared/.../api/GccApiClient.kt`, test in composeApp commonTest

- [ ] `CaptureUploadUrlRequest` gains `sessionId: String? = null`, `artifactPath: String? = null` (last params, defaulted). `EnvScanManifest` gains `captureChunks: List<String>? = null` (uploaded r2Keys) and `captureSchema: Int? = null` (sidecar format version, 1).
- [ ] Wire-compat test (composeApp commonTest): legacy JSON without the new fields decodes; round-trip with them.
- [ ] Gates + commit: `feat(capture): capture-chunk wire fields`

### Task 3: Chunk bookkeeping store (SQLDelight)

**Files:** new `shared/src/commonMain/sqldelight/com/gcc/shared/cache/CaptureQueue.sq`, new `composeApp/src/commonMain/kotlin/com/gcc/app/platform/capture/CaptureChunkStore.kt`, DI wiring in AppModule (follow ScanAreaCacheStore's pattern exactly), tests in composeApp commonTest (JVM driver — mirror ScanAreaCacheStoreTest's jvmTest placement if commonTest can't run it; check where its tests live NOW post-cleanup: shared/src/jvmTest).

- [ ] `.sq`: table captureChunk(sessionId TEXT, chunkIndex INTEGER, kind TEXT /*video|sidecar|depth*/, filePath TEXT, sizeBytes INTEGER, locationCellId TEXT, r2Key TEXT, uploadedAtMs INTEGER, createdAtMs INTEGER, PRIMARY KEY(sessionId, chunkIndex, kind)) + queries: insertChunk, markUploaded, pendingChunks (uploadedAtMs IS NULL ORDER BY createdAtMs), chunksForSession, deleteSession, orphanSessions (distinct sessionIds with pending chunks older than X).
- [ ] `CaptureChunkStore`: thin wrapper (suspend, Dispatchers.Default) exposing those ops; constructor takes the SqlDriver singleton like ScanAreaCacheStore.
- [ ] Tests: insert/pending/markUploaded/orphan queries. Gates + commit: `feat(capture): chunk bookkeeping store`

### Task 4: Swift ArSessionRecorder (THE core) — controller sequences AFTER any fastlane run

**Files:** new `iosApp/iosApp/ArSessionRecorder.swift` (+ pbxproj 4 entries), `iosApp/iosApp/GccArNative.swift`, `composeApp/src/iosMain/.../ArNativeSurface.kt` (protocol), `composeApp/src/iosMain/.../ArEnvScan.kt`

- [ ] `ArSessionRecorder` (class, main-actor-independent): `start(sessionId:outputDir:)`, `append(frame: ARFrame)` (called from `session(_:didUpdate:)` — MUST be allocation-light and never block: drop frames when writer isn't ready), `discardCurrentChunk()`, `pauseCapture()`, `resumeCapture()`, `finish() -> [FinishedChunk]` (struct: index, kind, filePath, sizeBytes). Internals: AVAssetWriter H.264/HEVC 1280×720 ~3Mbps from `frame.capturedImage` (writer input + pixel buffer adaptor, source time from frame.timestamp); rotate chunk at 60s OR 64MB (finalize writer async, enqueue FinishedChunk callback to Kotlin via a registered listener); JSONL sidecar per frame appended to a per-chunk `.jsonl` (fields: t, transform[16], intrinsics[9] from frame.camera.intrinsics, trackingState raw, thermal); depth keyframes at 2Hz: `frame.smoothedSceneDepth ?? frame.sceneDepth` depthMap → LZFSE via Compression framework → append length-prefixed records to per-chunk `.depth` file. Thermal policy inside append: `.serious` → skip video+depth appends (sidecar continues); `.critical` → pauseCapture() (and notify).
- [ ] `ArNativeSurface` protocol additions (iosMain interface + RealityKitArSurface impl): `startCapture(sessionId: String, outputDirPath: String)`, `stopCaptureAndList(): List<String>` (returns "index|kind|path|size" packets — K/N-friendly strings, precedent: classifySceneLabels packets), `discardCurrentCaptureChunk()`, `setCaptureChunkListener(...)` — or simplest: poll finished chunks from Kotlin each tick via `drainFinishedCaptureChunks(): List<String>`. CHOOSE the poll-drain design (no cross-language callback plumbing; the 1s Kotlin tick exists).
- [ ] Wire into `RealityKitArSurface.session(_:didUpdate:)`: `recorder?.append(frame:)` after the lastFrame store. `teardown()` → `recorder?.finish()` results drained by Kotlin before teardown (Kotlin stop order: stopCaptureAndList THEN teardown).
- [ ] pbxproj: 4 entries, fresh UUID pair, template CrashDiagnostics.swift lines (~14, ~28, ~73, ~191).
- [ ] Gates incl. FULL xcodebuild. Commit: `feat(capture): ArSessionRecorder — HEVC chunks, pose sidecar, LiDAR depth keyframes`

### Task 5: Kotlin orchestration + upload + UI chip

**Files:** new `composeApp/src/commonMain/kotlin/com/gcc/app/platform/capture/CaptureChunkUploader.kt`, new expect/actual `NetworkStatus` (iosMain NWPathMonitor; android/desktop stub "wifi"), `composeApp/src/iosMain/.../ArEnvScan.kt` (overlay capture API), `SpotScreen.kt`, `CameraCapture.kt` (chip), Settings key for cellular-upload opt-in

- [ ] `ArEnvScanOverlay` expect gains `startCapture(sessionId)/stopCapture(): List<CaptureChunkInfo>/discardCurrentCaptureChunk()` (+ no-op actuals android/desktop); iosMain maps to the native surface, output dir = Documents/capture-sessions/{sessionId} (NSFileManager, CrashDiagnostics dir pattern), drains finished chunks on the existing 1s tick → inserts rows via CaptureChunkStore (with locationCellId = gridCell10m of session anchor).
- [ ] SpotScreen: start capture when the AR overlay binds AND gate is OUTDOOR (listener on captureAllowed: pause/resume capture with it; INDOOR flip → `discardCurrentCaptureChunk()` — ties into the existing INDOOR block beside purgeAndRetractCoverage); stop+drain on scan completion (before overlay stop); recording chip in CameraCapture HUD (small red dot + "REC" when capturing, grey "REC ∅" when thermal-paused — param threaded EXPLICITLY through SpotScanCapture like prior params).
- [ ] `CaptureChunkUploader` (Koin singleton): `flushPending()` — for each pending chunk row: policy gate (NetworkStatus wifi OR charging OR cellular-opt-in Settings flag; expect/actual `isCharging()` iosMain UIDevice), presign via `createCaptureUploadUrl(... sessionId, artifactPath="capture/chunk-{idx}.{ext}")`, read file bytes (readLocalFileBytes precedent), `uploadToPresignedUrl`, markUploaded + delete file; poison: 4xx → mark uploaded=-1 (skipped) + log. Called: scan end, app launch (AppModule init hook where EnvScanSessionUploader.flushPending is called), and when NetworkStatus flips to wifi.
- [ ] Manifest: SpotViewModel attaches `captureChunks = store.chunksForSession(sessionId).mapNotNull { r2Key }` at manifest build (uploaded-so-far; late uploads are keyed by session in R2 regardless — note in KDoc).
- [ ] Gates + commit: `feat(capture): capture orchestration, policy-gated chunk upload, REC chip`

### Task 6: Pillar 6 — recorder durability + orphan recovery

**Files:** `composeApp/.../platform/EnvScanSessionRecorder.kt`, new expect/actual `AppendFile` util (iosMain NSFileHandle append; desktop java.io for tests), `EnvScanSessionUploader.kt`, launch hook

- [ ] Recorder gains optional journal: constructor takes `journalPath: String?`; addSample/noteGateEvent append one JSONL line (never throws — try/catch to DiagnosticsLog); purgeSince rewrites the journal (rare event, acceptable).
- [ ] Launch recovery: on app start (same place uploader.flushPending runs), scan Documents/scan-sessions/ for journals with no corresponding "completed" marker → rebuild a minimal EnvScanManifest (path samples + gateEvents from journal, empty items/photos, lapKind="recovered") → `uploadOrQueue` → delete journal. Completed scans write the marker (or just delete their journal on successful manifest build).
- [ ] SpotScreen/RouteScanViewModel recorder constructions pass journal paths (iosMain path provider; desktop/android null → pure in-memory as today).
- [ ] Tests (desktop actual): journal append + recovery parse round-trip. Gates + commit: `feat(capture): disk-backed session recorder + orphan recovery`

### Task 7: Verification + PR

- [ ] Full sweep: desktopTest, iOS link, FULL xcodebuild sim build, site tsc. `/simplify`-style pass over branch diff.
- [ ] PR (base main, NOT draft, merge = Blake's word) with: privacy semantics (chunk-discard on INDOOR), storage/battery policy table, R2 key layout, DGX consumption notes, deferred items (route-scan capture, real trailing-window truncation, upload progress UI).
