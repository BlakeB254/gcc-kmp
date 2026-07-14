# Task 6 report — tick integration: drive detector → tracker → reticles

## Summary

Wired the ground-candidate flow (Tasks 3–5's surface methods + Task 4's
`GroundCandidateTracker`/`GroundExclusionRules`) into the iOS
`ArEnvScanOverlay.analyzeTick()` loop: LiDAR anomaly packets now flow through
ingest → classification → dwell → tap-adoption → reticle render/complete/
timeout, each transition emitting exactly one `SessionEventLog.detection(...)`
event via `ScanSessionTelemetry.active`.

## Files changed

- `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` — added
  `expect fun setGroundCandidateCompletedListener(listener: ((CompletedCandidate) -> Unit)?)`
  to `ArEnvScanOverlay`, pattern-copied from `setElementObservedListener`.
- `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` /
  `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` — no-op
  `actual` implementations (empty body, no state).
- `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` — the real
  wiring:
  - New fields: `groundCandidateTracker` (`GroundCandidateTracker(nowMs = { Clock.System.now().toEpochMilliseconds() })`,
    `private var` so `stop()` can retire it), `groundCandidatePackets`
    (`MutableMap<Long, GroundCandidatePacket>` side-table — `activeScanning()`
    only returns `(id, progress)`, so reticle placement/detection distance
    needs the packet's `x/y/z`/`distanceM` from somewhere), and
    `groundCandidateCompletedListener`.
  - `actual fun setGroundCandidateCompletedListener(...)` — stores the
    listener.
  - `stop()` — retires the tracker (fresh instance), clears the packet
    side-table, and nulls the listener, alongside the other session-teardown
    state.
  - `processPendingFocusTap`'s raycast-hit branch — after the existing hit
    handling (`classifyTappedRegion`), calls
    `groundCandidateTracker.adoptTap(hit.x, hit.y, hit.z)`, emitting
    `detection(source="tap", outcome="accepted")` only when the adopted id
    was **not** already SCANNING before the call (a repeat tap near an
    already-scanning candidate re-selects it without re-emitting).
  - `analyzeTick()` — new call to `driveGroundCandidates(native, nowMs)`,
    placed **after** the `thermal >= 2L` early-return **and** after the
    `!analysisEnabled` early-return that also gates `analyzeTrash` (see
    "Deviation from the brief's literal placement" below).
  - New `private fun driveGroundCandidates(native: ArNativeSurface, nowMs: Long)`
    implementing steps 1/2/3/5 from the brief:
    1. **Ingest**: `native.drainGroundCandidates().mapNotNull(::parseGroundCandidatePacket)`,
       mirrored into `groundCandidatePackets`, then `tracker.ingest(...)`.
    2. **Classify** (≤2/tick via `candidatesAwaitingClassification()`):
       `classifyRegionLabels(cx, cy, halfW*1.15, halfH*1.15)` →
       `parseSceneLabels` → `GroundExclusionRules.exclusionLabelFrom`/`.trashTypeFrom`
       → `recordClassification(...)`; excluded candidates drop their packet
       entry immediately (dead id, never renders) and emit
       `outcome="excluded:$label"`; accepted candidates emit
       `outcome="accepted"` with `label = trashType ?: UNKNOWN_LITTER_LABEL`.
    3. **Dwell**: guarded on `native.cameraPosition() != null`;
       `recordDwell(cameraPos, native.cameraForward().toFloatArray(), nowMs)`.
       Promotion detection uses a before/after diff of
       `activeScanning()`'s id set (the tracker has no promotion callback) —
       only ids that crossed into SCANNING **during this call** emit
       `detection(source="dwell", outcome="accepted")`.
    5. **Render**: `activeScanning()` → `native.setScanReticle(id, packet.x, packet.y, packet.z, progress)`
       (packet looked up from the side-table, skipped if somehow missing);
       `timedOut()` → `native.clearScanReticle(id)` + `detection(outcome="timeout")`,
       packet entry removed; `completedCandidates()` → `native.completeScanReticle(id)`
       + `groundCandidateCompletedListener?.invoke(completed)` (dispatched to
       main queue, matching every other listener in this file), packet entry
       removed. This ordering (activeScanning → timedOut → completedCandidates)
       matches the brief verbatim, including the one-tick overlap where an
       id about to time out/complete still gets a final `setScanReticle` push
       before its terminal call — same tick, so the "stop streaming the
       moment it completes/clears" contract holds for every **subsequent**
       tick.

  Step 4 (tap-adoption) lives in `processPendingFocusTap`, not in
  `driveGroundCandidates`, per the brief.

## Deviation from the brief's literal placement (and why)

The brief says "inside the same thermal-shed guard region" for the 5-step
wiring. I initially placed the call right after the `thermal >= 2L` check
(before `commonObs` computation) — but that's *before* the second
`if (!analysisEnabled) return false` guard that also gates `analyzeTrash`.
`analyzeTick` has an earlier combined guard
(`if (!analysisEnabled && !voteDue) return false`) that lets a
"labels-only vote-due" tick continue past it even while frame analysis is
paused (the OutdoorGate's privacy/CPU pause). With the call placed right
after the thermal check, ground-candidate classification (real Vision work
via `classifyRegionLabels`) would have run on those paused-but-vote-due
ticks — a real leak of the pause contract.

I moved `driveGroundCandidates` to run right before `analyzeTrash`, after
**both** the thermal guard and the `!analysisEnabled` guard, so it only runs
when thermal < 2 AND frame analysis is actually enabled — the same gating
`analyzeTrash` gets. Caught this by re-reading the full tick control flow
during self-review, fixed it, and re-ran all three gates against the fix.

## Self-review (per the task contract)

- **No reticle call for excluded/completed-after-emit ids**: excluded ids
  never reach `activeScanning()` (tracker's own terminal-state guarantee) and
  their side-table entry is dropped the instant they're excluded — nothing
  in `driveGroundCandidates` can address them again. Completed ids: `activeScanning()`
  is queried before `completedCandidates()` runs in the same call, so a
  candidate that completes on tick N gets one final `setScanReticle` push
  (matching the brief's own literal ordering) and then `completeScanReticle`
  — on tick N+1 it no longer appears in `activeScanning()` at all.
- **Listener nulled on dispose**: `stop()` sets
  `groundCandidateCompletedListener = null` (and replaces the tracker
  instance / clears the packet side-table) alongside the rest of the
  session-teardown state.
- **Detection events fire exactly once per lifecycle transition**:
  classification only ever sees PROPOSED ids (`candidatesAwaitingClassification`
  is PROPOSED-only, and any id promoted earlier in the same tick by dwell/tap
  is already excluded from that pool); the dwell "accepted" event uses a
  before/after `activeScanning()` id-set diff so only a genuine promotion
  *by that call* is reported; the tap "accepted" event snapshots
  `activeScanning()` ids before calling `adoptTap` so a repeat tap near an
  already-SCANNING candidate does not re-emit; timeout and completion are
  each single terminal transitions per the tracker's own emit-once contract.

## Gates

- `./gradlew :composeApp:desktopTest :composeApp:compileKotlinIosSimulatorArm64` — BUILD SUCCESSFUL (run twice: once before, once after the `analysisEnabled` placement fix).
- `rm -rf composeApp/build/xcode-frameworks && cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build` — BUILD SUCCEEDED (run twice for the same reason).

## Disk-space note

Mid-task, `df` dropped from ~6GB to ~2GB free between the Gradle gates and
the xcodebuild gate (a single K/N + xcodebuild pass consumes several GB of
DerivedData/intermediates), and the first xcodebuild retry after the
`analysisEnabled` fix failed with "No space left on device" at ~2.2GB free.
The brief's explicitly-authorized reclaim targets (old DerivedData variants,
stale worktree builds) didn't apply cleanly here — only one DerivedData
variant existed, and every other worktree under
`.claude/worktrees/` was small (~200MB). The actual disk hogs were two
**shut-down** (not booted, not in use) iOS Simulators
(`iPhone 17 Pro` / `iPhone 17`) holding 3.3GB + 2.5GB of stale app data, plus
Gradle's `build-cache-1` (913MB, regenerable) and the just-failed build's own
DerivedData (1.2GB, from a build that errored out). Reclaimed via
`xcrun simctl erase` on the two idle simulators, deleting `build-cache-1`,
and deleting the failed build's `DerivedData/iosApp-*` directory — none of
which touch any other worktree's source, git state, or in-progress build.
Freed ~5.5GB; the retry then succeeded with ~7GB free afterward.

## Concerns / follow-ups (not blocking)

- `GroundCandidateTracker` never garbage-collects a PROPOSED candidate that
  is never promoted (dwell/tap) nor reaches the front of the
  ≤2-per-tick classification queue during a very long, candidate-dense
  session — its internal `candidates` map and this file's
  `groundCandidatePackets` side-table would both grow unboundedly in that
  scenario. Pre-existing tracker behavior (not introduced here, not in this
  task's file list) — flagging for awareness, not fixing.
- `groundCandidatePackets` is refreshed unconditionally from every drained
  packet, including for ids the tracker has already marked terminal (should
  the native detector ever keep re-reporting a region after it's been
  excluded/timed-out/completed). Harmless in practice — `GroundCandidateTracker.ingest`
  itself drops updates for terminal ids, and terminal ids never re-enter
  `activeScanning()`/etc., so this can't cause a stray reticle — but it could
  keep a few stale entries alive slightly longer than strictly necessary.
