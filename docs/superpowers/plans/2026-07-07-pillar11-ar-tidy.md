# Pillar 11 — AR Overlay Four-Systems Tidy: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** The env-scan AR overlay reduces to exactly four well-organized systems (ledger fog, stationary grid, analysis effects + real-time streaming, edge-arrow guidance); everything else quieted or cut. Owner: features mostly exist — organize, correct, declutter. Spec Pillar 11; ground truth = the 2026-07-07 overlay audit (in ledger).

## Global Constraints
- RUNS AFTER P8 MERGES (same ArEnvScan/SpotScreen files; P8 adds tap-to-focus — preserve it, it belongs to system 3/4).
- Gates: desktopTest + iOS link per task; full xcodebuild at sweep (Swift touched).
- Stability beats accuracy for the GRID ONLY; content (fog/quads/markers/arrow) keeps re-anchoring on geo-ref re-pairs.
- No new HUD chrome; guidance consolidates into ONE voice (kill duplicate captions).

### Task 1: Ledger fog (system 1)
**Audit refs:** CoverageTracker.kt:482 getUnscannedCells; SpotScreen.kt:633-637 serverCells fetched but never seeded; :2254-2278 neverScannedGrid (correct ledger logic, minimap-only); RouteScanViewModel.kt:454-461 seeds fresh-only.
- [ ] SpotScreen seeds the tracker with BOTH fresh and stale ledger cells (`seedScannedCells` — stale = previously scanned ≠ never-scanned; include 'seen'-quality ledger cells as scanned-for-fog per owner law: ledger presence lifts fog). RouteScanViewModel likewise seeds stale (currently fresh-only).
- [ ] Unify fog caps: RealityKit maxFogCells 600 (GccArNative.swift:62) vs SceneKit MAX_FOG_NODES 400 (ArEnvScan.kt:115) → one shared constant (400), radius aligned to the minimap's ledger-fog radius constant (SpotScreen.kt:2264-2266) with AR render radius still distance-capped.
- [ ] Test: tracker seeded with fresh+stale → getUnscannedCells excludes them; session SEEN still doesn't lift fog (existing semantics pinned).
- [ ] Commit: `feat(ar-tidy): fog keys on the ledger — never-scanned only`

### Task 2: Place-once grid (system 2)
**Audit refs:** GccArNative.swift:341-351 setGridPhaseOrigin re-phases on >5cm moves; Kotlin call sites (SpotScreen.kt:611-622 one-shot; verify route mode + any per-re-pair callers post-P8).
- [ ] `setGridPhaseOrigin`: first non-nil assignment is TERMINAL (`if gridPhaseOrigin != nil { return }`); document "stability beats accuracy — content re-anchors, the grid does not." SceneKit-path mirror gets the same one-shot lock (find its phase application near snapRangeToLattice usage).
- [ ] Verify route mode's per-fix `setGridLatticeOrigin` calls become harmless no-ops after first placement (they should — the Swift guard handles it; confirm the Kotlin layer doesn't maintain divergent state).
- [ ] Commit: `feat(ar-tidy): ground grid locks at first placement, never re-phases`

### Task 3: Analysis effects + real-time streaming (system 3)
**Audit refs:** emitLockEffect litter-only (ArEnvScan.kt:885); registerElementIfAny has NO effect (:898-955, world pos at :929); ArMarkerQueue 30s flush (SpotScreen.kt:582-587); batch endpoint accepts arrays.
- [ ] `registerElementIfAny` fires `emitLockEffect` (or a lighter cyan variant if trivially parameterizable — else same effect) at the landmark's world position on registration.
- [ ] Litter real-time: on confirm, immediately flush the marker queue (`flushArMarkers()` right after enqueue — single-item batch through the existing endpoint; keep the 30s loop as the retry sweep). Same immediate-kick for the observation path if separately queued.
- [ ] Marker discs (addMarker) re-scoped under system 3 as the persistent registration mark — keep.
- [ ] Commit: `feat(ar-tidy): analysis effect on every registration; litter posts on confirm`

### Task 4: One guidance voice + edge arrows (system 4)
**Audit refs:** duplicate move-closer captions (DetectionReticleOverlay.kt:124-129 candidate-distance vs SpotScanCapture.kt:74,282 route-distance); hold-steady prompt (SpotScreen.kt:860-862); 3D arrow aims but never screen-projects (GccArNative.swift:563-573); NEW CODE: screen-edge projection.
- [ ] Consolidate: ONE guidance caption slot (priority: hold-still [tracking LIMITED] > move-closer [candidate >2.2m] > directional [target off-screen] > frontier text); kill the duplicate/conflicting captions (keep the reticle's visual circle, drop its private caption).
- [ ] Edge arrows: project the active target (candidate world pos or frontier guidanceTarget) into screen space (camera projection via the native surface — add `projectToScreen(x,y,z): (sx,sy,onScreen)` to ArNativeSurface + Swift impl using ARView.project); when off-screen, render a Compose edge arrow at the clamped screen boundary pointing toward it; when on-screen, no arrow. Preserve P8's tap-to-focus + approach escalation as the close-range half of this voice.
- [ ] Commit: `feat(ar-tidy): single guidance voice with screen-edge arrows`

### Task 5: Declutter + sweep + PR
- [ ] Cut/quiet audit's non-system elements on the env path (sector posts/walls/veil confirmed route-only — verify env never calls; anything else surfaced). Confirm coaching overlay stays gated to grid acquisition.
- [ ] Merge origin/main, full gates + xcodebuild, /simplify pass, PR (merge on review approval — owner standing auth). Body: before/after element inventory table, the four corrections, deferred (motion-blur detection beyond tracking-state proxy).
