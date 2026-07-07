# r2a — Scan-end crash class fixes (CAMERA→RESULT / Complete Scan)

Branch: `worktree-v2-living-map` (based on `main`)

## Fix 1 — Real AR teardown (not just pause)

**Swift — `iosApp/iosApp/GccArNative.swift`**
- Added `RealityKitArSurface.teardown()` (after the existing `pause()`, ~line 130):
  - Cancels `updateSubscription` and sets it `nil` (the `SceneEvents.Update`
    subscription that stayed alive after `pause()`, still driving `aimArrow()`
    every frame against a scene with 750+ entities).
  - Removes every anchor from the scene and clears the backing collections:
    `planeEntities`, `vectorCellEntities`, `fogCellEntities`,
    `sectorPostEntities`, `wallEntities`.
  - Detaches `arrowEntity`/`veilEntity` (children of `cameraAnchor`), then
    removes `cameraAnchor` itself and nils both entity refs.
  - Deactivates and nils `coachingOverlay`.
  - Clears `lastFrame` under `frameLock`, then `arView.session.pause()`.
  - Clears the static `liveSurface` if `=== self` (same guard as `pause()`).

**Kotlin protocol — `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArNativeSurface.kt`**
- Added `fun teardown()` to the `ArNativeSurface` interface (iosMain-only,
  single Swift conformer verified via grep — no other implementers to update).

**Kotlin overlays — all three `stop()` call sites switched `nativeSurface?.pause()`
→ `nativeSurface?.teardown()` followed by `nativeSurface = null`, keeping the
existing job/coroutine-cancellation and `frameSourceClosure` identity-check
logic untouched:**
- `ArEnvScan.kt` (`ArEnvScanOverlay.stop()`, ~253): also now clears the
  SceneKit fallback's `planeNodes`, `vectorNodes`, `fogNodes` maps
  (`removeFromParentNode()` each, then `.clear()`), plus `arrowNode` /
  `frontierTarget`.
- `ArRouteOverlay.kt` (`ArRouteOverlay.stop()`, ~169): clears `fogNodes`,
  `vectorNodes`, `boundaryWallNodes`, `boundaryVeil`, `arrowNode`,
  `frontierTarget`.
- `SpotArOverlay.kt` (`SpotArOverlay.stop()`, ~102): clears `sectorNodes`,
  `arrowNode`, `guideTarget`.

## Fix 2 — Gate GccMap's layer construction on supplied data

File: `composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt`.
z-order preserved throughout — every group was wrapped **in place**; no
group was moved relative to any other. Groups that already had a condition
(coverage-state tiles on `coverageStateJsons != null`, affected blocks on
`affectedBlocks.isNotEmpty()`, user-location dot on `currentLocation != null`,
the spot-clean-zone scrim FillLayer on `spots.isNotEmpty()`) were left
untouched.

Groups newly gated (source `remember` + layer declarations both skipped when
empty):

1. **Spot-density heat surface** (5 sources + 5 `CircleLayer`s, the existing
   scrim conditional nested inside, now redundant but harmless) — gated on
   `spots.isNotEmpty()`.
2. **Route fill target + 3-layer line stack** (glow/casing/core) — gated on
   `routeCoordinates.isNotEmpty()`. `routesSrc`/`routePolygonsSrc` moved from
   the top-level source block into this group.
3. **Route corridor band + inner-boundary (fill/casing/line) + checkpoint
   circles** — kept as its own group (not merged with #2) because the
   coverage-state tile stack sits between them in the original file and must
   not move — gated on `routeCoordinates.isNotEmpty()`. `routeInnerSrc`,
   `routeCorridorSrc`, `checkpointsSrc` moved in.
4. **Photo star markers** (checkpoint photos) — gated on
   `checkpointPhotos.isNotEmpty()`.
5. **Image location diamond markers** — gated on `imageStars.isNotEmpty()`.
6. **Bounty markers** (radius, dots, amount labels) — gated on
   `bounties.isNotEmpty()`.
7. **Spot rectangle fills + spot report markers (halos, bountied halos, tap
   target, dots, typed dots, litter glyphs) + verification ring** — one
   contiguous block, gated on `spots.isNotEmpty()`.

Not touched: `showBuildings`, `showGridLines`, `explorerAreaStates` — grepped
the composable body and none of them drive an unconditional layer stack in
this file (likely consumed elsewhere / reserved); the "Explorer mode" area
labels (`explorerLabelsJson`/`explorerSrc`) were deliberately left ungated —
they render area names regardless of bounty count, so gating on
`bounties.isNotEmpty()` would have been a visual regression, not a fix.

This directly targets `ScanSessionSummaryCard`'s `GccMap(...)` call, which
passes no routes/spots/bounties/etc. — it now allocates only the always-on
sources (state/city/area/service-area/scrim boundary polygons) instead of
the full stack.

## Fix 3 — Unguarded coroutine + NaN inputs

- **`composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt`**
  - `addObservedItem`'s `scope.launch { arMarkerQueue.enqueue(...) }` wrapped
    in `try/catch (e: Exception)` with `DiagnosticsLog.error("env-scan", ...)`
    — matches the file's other guarded launches.
  - `attachObjectCapture` (~336): added an upfront `isFinite()` guard over
    `outcome.widthMeters/heightMeters/depthMeters/volumeLiters`; non-finite
    values log an error and `return` before constructing `ObjectCaptureResult`
    (a `@Serializable` type with non-nullable `Double` fields — encoding NaN/
    Infinity throws under kotlinx.serialization's default JSON config) or
    kicking off the USDZ upload.

- **`iosApp/iosApp/ObjectCapturePhotogrammetry.swift`**
  - `emitResult` (~197): added a finite-AND-positive check over the `.bounds`
    extents (width/height/depth). Degenerate boxes now report dims as `0`
    (the same convention the existing `.zero`/failure paths already use —
    `onResult`'s closure type takes non-optional `KotlinDouble`, so `nil` was
    not an available signal) instead of propagating NaN/Infinity into the
    volume math and across the Kotlin bridge.

- **`composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`**
  - Trash-candidate path (`analyzeTrash`, ~682): added
    `if (!lat.isFinite() || !lng.isFinite()) return` right after the
    `localToLatLng` call, before constructing `TrashCandidate`.
  - Landmark-element path (`registerElementIfAny`, ~749): same guard before
    invoking `elementObservedListener`.
  - (`physicalWidthMeters` already had an `isFinite()` + range guard — left
    untouched.)

## Fix 4 — Breathing room at the transition

File: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/ScanSessionSummaryCard.kt`,
inside the private `SessionCoverageMap` composable (the map actually used by
the summary card).

- Added `var showMap by remember { mutableStateOf(false) }` +
  `LaunchedEffect(Unit) { delay(150); showMap = true }`.
- `GccMap(...)` now renders only `if (showMap)`; otherwise a
  `Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))`
  placeholder fills the same `BoxWithConstraints` bounds so layout doesn't
  jump when the map mounts a beat later.
- The coverage-cell `Canvas` overlay (drawn after the map) was left as-is —
  it's a cheap draw pass with no native surface allocation, not part of the
  crash window this delay is closing.

## Verification

- `./gradlew :composeApp:desktopTest` → **BUILD SUCCESSFUL** (41s). Exercises
  commonMain compilation for `SpotViewModel.kt`, `ScanSessionSummaryCard.kt`,
  and desktop-target compilation of the touched `platform/Ar*.kt` files.
- `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` → **BUILD
  SUCCESSFUL** (4m3s). Compiles+links all iosMain Kotlin changes (`ArEnvScan.kt`,
  `ArNativeSurface.kt`, `ArRouteOverlay.kt`, `SpotArOverlay.kt`,
  `GccMap.ios.kt`) into the Kotlin/Native framework.
- `rm -rf composeApp/build/xcode-frameworks && xcodebuild -project
  iosApp/iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS
  Simulator' build` → **BUILD SUCCEEDED**. Compiled both
  `GccArNative.swift` and `ObjectCapturePhotogrammetry.swift` cleanly (arm64 +
  x86_64 slices), no errors.
- `git status --porcelain iosApp/iosApp.xcodeproj` → empty both before and
  after; no new files added under `iosApp/`. Final changed-file list is
  exactly the 9 files from the four fixes above (no `.pbxproj` diff).

## Commit

`fix(scan-end): tear down AR scene fully, gate map layers on data, guard NaN/coroutine crash paths`
