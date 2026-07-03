# AR Scan Experience Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Full-screen spot camera with one-tap Complete Scan, an indoor/outdoor gate that blocks scanning indoors, and live AR feedback (server-seeded fog, trash highlighting, points HUD, expected-observation rectify flow).

**Architecture:** Compose Multiplatform (composeApp common UI + expect/actual platform layer; iOS actuals are the only real AR implementations, Android/Desktop are stubs). New pure-Kotlin state machines live in commonMain with commonTest coverage; ARKit/Vision work extends the existing `ArRouteOverlay`/`SpotArOverlay` iosMain actuals.

**Tech Stack:** Kotlin Multiplatform, Compose MP, ARKit (`ARSCNView` + SceneKit via cinterop), Apple Vision (`VNClassifyImageRequest`, saliency), Ktor client (`GccApiClient`), Next.js `site/` APIs (already deployed).

## Global Constraints

- Every expect-class member added in commonMain MUST get actuals in `iosMain`, `androidMain`, `desktopMain` (stubs: no-op / empty StateFlow) or the Android/desktop targets stop compiling.
- iOS interop stays SceneKit/ObjC-compatible (no RealityKit) — pattern per `ArRouteOverlay.kt:29-45`.
- All new floating chrome uses `statusBarsPadding()`/`navigationBarsPadding()`, never hardcoded 48/96 dp.
- Server APIs are fixed: `GET /api/map/coverage?bbox=`, `GET /api/map/observations?bbox=`, `POST /api/scans/coverage`, `POST /api/scans/{id}/rectify` (`{lat,lng}` body; route-scan-owned only).
- Rates mirrored client-side: new-area 10 pts/m, stale-rescan 5 pts/m, fresh 0 (per `site/src/lib/points-config.ts`) — estimator display only, server ledger is truth.
- Commit after every task; run `./gradlew :composeApp:allTests` (or at minimum `:composeApp:iosSimulatorArm64Test` + desktop test) before each commit; final gate is the simulator framework link + xcodebuild.
- Do not touch TestFlight/fastlane in this plan.

---

### Task 1: `OutdoorGate` state machine (commonMain + tests)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/OutdoorGate.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/OutdoorGateTest.kt`

**Interfaces:**
- Produces: `enum OutdoorState { OUTDOOR, UNCERTAIN, INDOOR }`, `enum SceneVote { INDOOR, OUTDOOR, NONE }`,
  `class OutdoorGate(clock: () -> Long)` with `fun onAccuracySample(horizontalAccuracyMeters: Double?, timestampMs: Long)`,
  `fun onSceneVote(vote: SceneVote, timestampMs: Long)`, `val state: StateFlow<OutdoorState>`.

Rules (encode exactly):
- Evidence window: 15s rolling. INDOOR requires ≥5s of consistent indoor evidence; OUTDOOR recovery requires ≥5s of outdoor evidence (hysteresis — no flapping on single samples).
- Accuracy votes: `> 25m` → indoor evidence; `< 10m` → outdoor evidence; between → neutral; `null` → neutral.
- Scene votes count double weight (camera sees the room). A scene INDOOR vote plus any indoor accuracy sample within the window forces INDOOR immediately (both signals agree).
- No evidence at all → `UNCERTAIN` (never blocks).

- [ ] Write failing tests: starts UNCERTAIN; sustained bad accuracy (6s of >25m samples) → INDOOR; recovery after 5s of <10m → OUTDOOR; single bad sample does NOT flip; scene INDOOR + bad accuracy → INDOOR immediately; null accuracy stays UNCERTAIN.
- [ ] Run `./gradlew :composeApp:desktopTest --tests '*OutdoorGateTest*'` — expect FAIL (class missing).
- [ ] Implement `OutdoorGate` (pure, injectable clock, no coroutines needed beyond MutableStateFlow).
- [ ] Tests pass. Commit `feat(gate): OutdoorGate indoor/outdoor state machine`.

### Task 2: GPS accuracy + scene-vote plumbing (platform layer)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/LocationProvider.kt` (add `val horizontalAccuracyMeters: StateFlow<Double?>`)
- Modify: `composeApp/src/iosMain/.../LocationProvider.kt` (populate from `CLLocation.horizontalAccuracy` in the existing didUpdateLocations path; negative accuracy → null)
- Modify: `composeApp/src/androidMain/.../LocationProvider.kt` (`location.accuracy` when `hasAccuracy()`)
- Modify: `composeApp/src/desktopMain/.../LocationProvider.kt` (`MutableStateFlow(null)`)
- Modify: commonMain + 3 actuals of `ArRouteOverlay.kt` and `SpotArOverlay.kt`: add `fun setSceneVoteListener(listener: ((SceneVote) -> Unit)?)` (stub actuals: store nothing).
- Create: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArSceneClassifier.kt`

**Interfaces:**
- Produces: `ArSceneClassifier(frameImage: () -> Any? /* CVPixelBuffer via session.currentFrame */, onVote: (SceneVote) -> Unit)` — iOS-internal; samples one AR frame every 2s on a background dispatcher, runs `VNClassifyImageRequest` (pattern: `OnDeviceAnalyzer.kt:43-52,147` but with `VNImageRequestHandler(cvPixelBuffer:)`), votes INDOOR when top classifications contain any of `indoor, room, furniture, ceiling, wall, kitchen, bathroom, bedroom, office, hallway` with confidence ≥ 0.4; OUTDOOR for `outdoor, street, road, sidewalk, building, sky, tree, grass, park` ≥ 0.4; else NONE. `start()`/`stop()`.
- Both iOS overlay actuals own an `ArSceneClassifier`, started in `bind(view)` when a listener is set, stopped in `stop()`.

- [ ] Add expect members + all actuals; iOS classifier implementation.
- [ ] `./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid` compile green (iOS compiles in Task 9 gate).
- [ ] Commit `feat(gate): expose GPS accuracy + AR scene votes from platform layer`.

### Task 3: Wire outdoor gating into the three scan surfaces

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/GoOutsideOverlay.kt`
- Modify: `SpotScreen.kt` CameraPhase, `SpotArScreen.kt`, `ArRouteScreen.kt`

**Interfaces:**
- `GoOutsideOverlay(visible: Boolean, modifier)` — full-screen `Color.Black.copy(alpha=0.78f)` scrim, 🏠➜🌳 icon text, headline "Go outside to scan", body "Scanning only works outdoors in public areas. Step outside and the camera will unlock.", blocks clicks (`pointerInput` consume).

Wiring in each surface: `val gate = remember { OutdoorGate() }`; LaunchedEffect collecting `locationProvider.horizontalAccuracyMeters` → `gate.onAccuracySample`; AR surfaces also `overlay.setSceneVoteListener { gate.onSceneVote(it, now) }`. When `gate.state == INDOOR`: show overlay, AND
- SpotScanCapture: `canCapture` becomes `canCapture && !isIndoor` (pass `isIndoor` param down from CameraPhase).
- SpotArScreen/ArRouteScreen coverage loops: skip `queue.enqueue`/tracker updates while INDOOR.

- [ ] Implement + wire all three; desktop compile + existing tests green.
- [ ] Commit `feat(gate): Go Outside overlay disables scanning indoors`.

### Task 4: Full-screen spot camera + one-tap Complete Scan

**Files:**
- Modify: `SpotScreen.kt` (`topBar` at :80-117 — render `null`-equivalent when `uiState.phase == SpotPhase.CAMERA`; CameraPhase :207-277 restructure)
- Modify: `SpotViewModel.kt` (add `fun completeScan()`)

**Interfaces:**
- `SpotViewModel.completeScan()`: requires ≥1 photo; auto-applies AI description (`aiAnalysis.generatedDescription` when `description` blank) and AI tags (already prefilled by `addPhoto`), then calls the existing `submitReport()` directly from CAMERA phase (skips LOCATION/DESCRIPTION; `spotLat` null → GPS per `submitReport` :271-272). On submit error `submitReport` already falls back to `DESCRIPTION` phase — acceptable (user lands in manual details with the error shown).

CameraPhase becomes a full-bleed `Box(Modifier.fillMaxSize())` (no `padding(16.dp)` Column, no `ScanModeBadge` header row — `CaptureModeBadge` inside `SpotScanCapture` already shows device class):
- camera subtree (`SpotScanCapture`/`CameraCapture`) `Modifier.fillMaxSize()`,
- floating ✕ back button top-left (`statusBarsPadding().padding(8.dp)`, circle style copied from `SpotArScreen.kt:189-204`) → `onBack`,
- **Complete Scan** pill bottom-right: `navigationBarsPadding().padding(16.dp)`, `GccButton`-styled rounded pill, enabled only when `photos.isNotEmpty()`, label "Complete Scan ✓" → `viewModel.completeScan()`,
- "Add details" `TextButton` bottom-left (`navigationBarsPadding()`) → `viewModel.nextPhase()` (old manual flow; keeps duplicate-check path alive),
- "Skip Photo"/"Next: Set Location" `GccButton` (:271-275) deleted.
- `Scaffold.topBar` renders the existing Row only when `uiState.phase != SpotPhase.CAMERA` (LOCATION/DESCRIPTION/SUBMITTING/RESULT keep the header).
- Move the AR-guide button inside `SpotScanCapture` up (`padding(bottom = 88.dp)`) so Complete Scan owns the corner.

- [ ] Restructure, compile desktop, screenshot check via desktop run if feasible.
- [ ] Commit `feat(spot): full-screen camera phase with one-tap Complete Scan`.

### Task 5: Safe-area insets + Done affordances on AR screens

**Files:** `SpotArScreen.kt`, `ArRouteScreen.kt`

- Replace `padding(top = 48.dp)` → `statusBarsPadding().padding(top = 8.dp)`; `padding(bottom = 96.dp)` → `navigationBarsPadding().padding(bottom = 24.dp)` (all occurrences in both files).
- SpotArScreen: add "Done" pill bottom-right (`navigationBarsPadding().padding(16.dp)`) → `onExit` (captures continue in SpotScanCapture).
- ArRouteScreen: add "Back to Map" pill bottom-right → `onExit` (scout completion stays on ScoutScreen's existing button — route AR is a scouting aid, `completeScout` needs the map review context).
- [ ] Commit `fix(ar): safe-area insets + exit affordances on AR screens`.

### Task 6: Server-seeded fog (route AR)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageTracker.kt` — add `fun cellForLatLng(lat: Double, lng: Double): Pair<Int, Int>?` (inverse of `cellCenterLatLng`, `:129-130` math; null when outside grid) and `fun seedScannedCells(cells: Set<Pair<Int, Int>>)` (marks cells scanned without awarding session progress — keep a separate `preSeeded` set so `coveragePercent` still reflects the worker's own session).
- Modify: `RouteScanViewModel.kt` — in `beginRoute()` (:350): launch a fetch `apiClient.getMapCoverage(bbox)` (bbox from route waypoint min/max ±50m, format matching `/api/map/coverage`'s `west,south,east,north` — confirm against `GccMap`'s existing `getMapCoverage` call site); cells with `state == "fresh"` → `coverageTracker.cellForLatLng` → `seedScannedCells`. Store `cellPriorStates: Map<Pair<Int,Int>, String>` ("fresh"/"stale") on the VM for Task 7's estimator. Failures: log, continue (fog stays session-local).
- Test: `composeApp/src/commonTest/.../CoverageTrackerSeedTest.kt` — seeding removes cells from `getUnscannedCells()` but does not change `coveragePercent`'s denominator behavior for the session.

- [ ] TDD the tracker changes; wire VM; commit `feat(ar): seed route fog from production coverage map`.

### Task 7: Live session points HUD + floating pips

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/SessionPointsEstimator.kt`
- Test: `composeApp/src/commonTest/.../SessionPointsEstimatorTest.kt`
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/ArHudChrome.kt` (points pill + `PointsPip` floating "+N pts" animation: `animateFloatAsState` drift-up/fade, keyed list)
- Modify: `RouteScanViewModel.kt` (feed estimator in the coverage enqueue path :598-609 using `cellPriorStates`; expose `sessionPointsEstimate: StateFlow<Int>` + `SharedFlow<Int>` pip events), `ArRouteScreen.kt`, `SpotArScreen.kt` (HUD pill next to coverage pill; spot mode estimates with NEW_AREA rate only when the covered cell wasn't previously covered this session).

**Interfaces:**
- `SessionPointsEstimator` pure: `fun onSegment(cell: Pair<Int,Int>?, priorState: String? /* null=never scanned, "stale", "fresh" */, meters: Double): Int` returning points delta (10/m, 5/m, 0), `val total: Int`. Caps single segment at 50m (GPS jump guard, mirrors server's plausibility rejection from PR #11).

- [ ] TDD estimator; build HUD; wire both screens; commit `feat(points): live points HUD + pips during AR scanning`.

### Task 8: Trash highlighting + persistent markers + expected-observation rectify

**Files:**
- Modify commonMain + 3 actuals `ArRouteOverlay.kt` / `SpotArOverlay.kt`:
  - `val liveDetections: StateFlow<List<LiveDetection>>` — `data class LiveDetection(val cx: Float, val cy: Float, val r: Float, val label: String, val distanceMeters: Float?)` (normalized view coords),
  - `fun addWorldMarker(lat: Double, lng: Double, kind: WorldMarkerKind)` — `enum WorldMarkerKind { CONFIRMED_TRASH, EXPECTED_TRASH }`.
- Create: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/LiveDetector.kt` — 1Hz loop on `session.currentFrame.capturedImage`: `VNClassifyImageRequest` (trash keyword map reused from `OnDeviceAnalyzer.kt:45-107`) + `VNGenerateObjectnessBasedSaliencyImageRequest` for the salient bounding box; converts Vision normalized rect → view coords with `ARFrame.displayTransformForOrientation`; distance from LiDAR/`hitTest` at rect center; backs off to 3s cadence after a Vision error. When a detection sits < 2m for 2 consecutive ticks → raycast to ground → `addWorldMarker(..., CONFIRMED_TRASH)` at that world point (SceneKit: flat `SCNTorus` ring, green; EXPECTED_TRASH = orange, gentle `SCNAction` pulse) and suppress re-adding within 3m of an existing marker.
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/DetectionReticleOverlay.kt` — Compose `Canvas` drawing a circle + label per `LiveDetection`.
- Modify: `RouteScanViewModel.kt`: on `beginRoute()` also `apiClient.getMapObservations(bbox)`; keep `state == "dirty"` list; feed `overlay.addWorldMarker(..., EXPECTED_TRASH)` (via ArRouteScreen); proximity flow `nearbyExpectedObservation: StateFlow<WorldObservation?>` (within 5m, not yet resolved this session).
- Modify: `ArRouteScreen.kt`: reticle overlay + when `nearbyExpectedObservation != null` show bottom sheet: "Expected trash reported here — what do you see?" buttons **Still there** (dismiss, mark session-resolved) / **Already clean** (→ `viewModel.rectifyObservation(obs)`).
- Modify: `shared/.../api/GccApiClient.kt`: `suspend fun rectifyScanObservation(scanId: Int, lat: Double, lng: Double): RectifyResponse` → `POST /api/scans/{id}/rectify`; add `RectifyResponse` model matching the route's JSON (read tail of `site/src/app/api/scans/[id]/rectify/route.ts` for exact field names, expect `{success, pointsAwarded, observation}`); VM adds `pointsAwarded` to the pip stream.
- Modify: `SpotArScreen.kt`: reticle overlay + confirmed-marker placement only (no rectify — endpoint requires a route scan).

- [ ] Implement (compile stubs first, then iOS), commit `feat(ar): live trash detection, world markers, expected-observation rectify flow`.

### Task 9: Verification gate

- [ ] `./gradlew :composeApp:desktopTest` and `:composeApp:compileDebugKotlinAndroid` — green.
- [ ] `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` — green (first full iOS interop compile; fix errors here).
- [ ] `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build` — green. (Clear `~/Library/Developer/Xcode/DerivedData` first if disk-pressure errors appear.)
- [ ] Simulator launch + screenshot of the spot camera phase (chrome check only — AR/GPS won't function).
- [ ] Commit any fixes, push branch, `gh pr create --draft` with a physical-device test checklist (outdoor gate, reticle accuracy, marker anchoring, fog seeding, one-tap submit).

## Self-review notes

- Spec §1 one-tap submit → Task 4; §1 insets/AR done buttons → Task 5; §2 gate → Tasks 1-3; §3a fog → Task 6; §3b highlight/recreate → Task 8; §3c points → Task 7; §3d rectify → Task 8. Rectify moved route-AR-only (endpoint requires owned route scan — spec §3d amended by this plan).
- Type checks: `SceneVote` defined Task 1, consumed Tasks 2-3. `cellPriorStates` produced Task 6, consumed Task 7. `LiveDetection`/`WorldMarkerKind` defined and consumed in Task 8 only.
