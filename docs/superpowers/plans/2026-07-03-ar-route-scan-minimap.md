# AR Route-Scan Minimap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a live minimap (fog/stale/scanned-cell coloring + trash pins) on the AR route-scan screen, matching the one that already exists for spot-mode capture.

**Architecture:** Reuse the existing `ScanMinimapOverlay` composable (commonMain, unchanged) and the coverage-priors data `RouteScanViewModel` already fetches for points estimation — extract a small, pure, testable classification function that splits "not yet scanned" cells into fog (never scanned by anyone) vs. stale (scanned before, but long enough ago the ledger marks it stale), expose the result as two new `StateFlow`s, and render `ScanMinimapOverlay` in `ArRouteScreen.kt`.

**Tech Stack:** Kotlin Multiplatform (commonMain), Compose Multiplatform, `kotlin.test` for unit tests.

## Global Constraints

- No new dependencies.
- `ScanMinimapOverlay.kt` is not modified — reuse its existing public API exactly.
- `RouteScanViewModel` must not perform a second network fetch — `seedFogFromServerCoverage()` (RouteScanViewModel.kt:197-244) already fetches everything needed (`cellPriorStates: Map<Pair<Int,Int>, String>`, values `"fresh"` or `"stale"`); this plan only adds a derived, reactive projection of data already in memory.

---

### Task 1: Pure fog/stale classification function + test

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/RouteFogClassification.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/RouteFogClassificationTest.kt`

**Interfaces:**
- Produces: `data class RouteFogClassification(val fogCells: Set<Pair<Int, Int>>, val staleCells: Set<Pair<Int, Int>>)` and `fun classifyFogAndStale(unscannedCells: Set<Pair<Int, Int>>, cellPriorStates: Map<Pair<Int, Int>, String>): RouteFogClassification` — consumed by Task 2.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteFogClassificationTest {

    @Test
    fun cellAbsentFromPriorsIsFog() {
        val result = classifyFogAndStale(
            unscannedCells = setOf(0 to 0),
            cellPriorStates = emptyMap(),
        )
        assertEquals(setOf(0 to 0), result.fogCells)
        assertEquals(emptySet(), result.staleCells)
    }

    @Test
    fun cellMarkedStaleInPriorsIsStale() {
        val result = classifyFogAndStale(
            unscannedCells = setOf(1 to 1),
            cellPriorStates = mapOf((1 to 1) to "stale"),
        )
        assertEquals(emptySet(), result.fogCells)
        assertEquals(setOf(1 to 1), result.staleCells)
    }

    @Test
    fun cellMarkedFreshInPriorsIsNeitherFogNorStale() {
        // "fresh" priors are folded directly into CoverageTracker.seedScannedCells
        // by RouteScanViewModel — they never appear in unscannedCells in the
        // first place, but this guards the function's own contract regardless.
        val result = classifyFogAndStale(
            unscannedCells = setOf(2 to 2),
            cellPriorStates = mapOf((2 to 2) to "fresh"),
        )
        assertEquals(emptySet(), result.fogCells)
        assertEquals(emptySet(), result.staleCells)
    }

    @Test
    fun mixedSetPartitionsCorrectly() {
        val result = classifyFogAndStale(
            unscannedCells = setOf(0 to 0, 1 to 1, 2 to 2),
            cellPriorStates = mapOf((1 to 1) to "stale"),
        )
        assertEquals(setOf(0 to 0, 2 to 2), result.fogCells)
        assertEquals(setOf(1 to 1), result.staleCells)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.RouteFogClassificationTest"`
Expected: FAIL — "Unresolved reference: classifyFogAndStale" (the function doesn't exist yet)

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.gcc.app.platform

/**
 * Split a route-scan's not-yet-scanned cells into "fog" (never scanned by
 * anyone, per the production coverage ledger) vs. "stale" (scanned before,
 * but the ledger marks the last scan old enough to need a re-visit).
 *
 * A cell absent from [cellPriorStates] was never scanned by anyone (fog).
 * A cell present with state "stale" needs a re-visit. A cell present with
 * state "fresh" shouldn't appear in [unscannedCells] at all — RouteScanViewModel
 * folds fresh priors directly into CoverageTracker's scanned-cell set before
 * this function ever runs — but is classified as neither here regardless,
 * so the function's output is correct even if called with an unexpected input.
 */
data class RouteFogClassification(
    val fogCells: Set<Pair<Int, Int>>,
    val staleCells: Set<Pair<Int, Int>>,
)

fun classifyFogAndStale(
    unscannedCells: Set<Pair<Int, Int>>,
    cellPriorStates: Map<Pair<Int, Int>, String>,
): RouteFogClassification {
    val staleCells = unscannedCells.filterTo(mutableSetOf()) { cellPriorStates[it] == "stale" }
    val fogCells = unscannedCells.filterTo(mutableSetOf()) { it !in cellPriorStates }
    return RouteFogClassification(fogCells = fogCells, staleCells = staleCells)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.RouteFogClassificationTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/RouteFogClassification.kt composeApp/src/commonTest/kotlin/com/gcc/app/platform/RouteFogClassificationTest.kt
git commit -m "Add pure fog/stale cell classification for route-scan minimap"
```

---

### Task 2: Expose fog/stale/trash StateFlows from RouteScanViewModel

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt:143-146` (make `cellPriorStates` reactive instead of a plain `private var`)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt:197-244` (`seedFogFromServerCoverage` — push into the new StateFlow instead of the plain var)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt:265-278` (add the two new public projections next to `scannedCells`/`unscannedCells()`)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt:350-355` (`initForRoute`'s `coverageJob` — also recompute fog/stale on every scanned-cell change)

**Interfaces:**
- Consumes: `classifyFogAndStale` and `RouteFogClassification` from Task 1 (`com.gcc.app.platform` package, same package as this file — no new import needed).
- Produces: `val fogCellCenters: StateFlow<List<Pair<Double, Double>>>`, `val staleCellCenters: StateFlow<List<Pair<Double, Double>>>` on `RouteScanViewModel` — consumed by Task 3.

- [ ] **Step 1: Replace the plain `cellPriorStates` var with a `MutableStateFlow` and add the two derived output flows**

Change (RouteScanViewModel.kt:143-146), from:

```kotlin
    // Prior coverage state per client grid cell ("fresh" | "stale"), from the
    // production ledger. Cells absent from the map were never scanned.
    // Consumed by the live points estimator; "fresh" cells also start revealed.
    private var cellPriorStates: Map<Pair<Int, Int>, String> = emptyMap()
```

to:

```kotlin
    // Prior coverage state per client grid cell ("fresh" | "stale"), from the
    // production ledger. Cells absent from the map were never scanned.
    // Consumed by the live points estimator; "fresh" cells also start revealed.
    private val _cellPriorStates = MutableStateFlow<Map<Pair<Int, Int>, String>>(emptyMap())
    private var cellPriorStates: Map<Pair<Int, Int>, String>
        get() = _cellPriorStates.value
        set(value) { _cellPriorStates.value = value }

    // Minimap projections (route-scan AR screen): fog = never scanned by
    // anyone; stale = scanned before, ledger says re-visit. Both recompute
    // whenever either the server priors arrive or a cell gets scanned this
    // session (see the coverageJob collector in initForRoute).
    private val _fogCellCenters = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val fogCellCenters: StateFlow<List<Pair<Double, Double>>> = _fogCellCenters.asStateFlow()
    private val _staleCellCenters = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val staleCellCenters: StateFlow<List<Pair<Double, Double>>> = _staleCellCenters.asStateFlow()

    private fun refreshFogAndStaleCenters() {
        val classification = classifyFogAndStale(coverageTracker.getUnscannedCells(), cellPriorStates)
        _fogCellCenters.value = classification.fogCells.map { (row, col) -> coverageTracker.cellCenterLatLng(row, col) }
        _staleCellCenters.value = classification.staleCells.map { (row, col) -> coverageTracker.cellCenterLatLng(row, col) }
    }
```

(`cellPriorStates` keeps its existing call sites working unchanged — every existing read/write in this file goes through the same property name, now backed by the StateFlow.)

- [ ] **Step 2: Trigger a refresh whenever priors arrive, and whenever a cell gets scanned**

In `seedFogFromServerCoverage` (RouteScanViewModel.kt:219, right after `cellPriorStates = priors`), add one line:

```kotlin
                cellPriorStates = priors
                refreshFogAndStaleCenters()
```

In `initForRoute` (RouteScanViewModel.kt:351-355), change:

```kotlin
        // Observe coverage changes
        coverageJob = scope.launch {
            coverageTracker.coveragePercent.collect { pct ->
                _uiState.value = _uiState.value.copy(coveragePercent = pct)
            }
        }
```

to:

```kotlin
        // Observe coverage changes
        coverageJob = scope.launch {
            coverageTracker.coveragePercent.collect { pct ->
                _uiState.value = _uiState.value.copy(coveragePercent = pct)
            }
        }
        scope.launch {
            coverageTracker.scannedCells.collect { refreshFogAndStaleCenters() }
        }
```

(A cell moving from unscanned → scanned is exactly the event that should shrink the fog/stale sets — this collector fires on every such change, in addition to the one-time refresh when server priors first arrive.)

- [ ] **Step 3: Build to confirm no compile errors**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt
git commit -m "Expose fog/stale cell-center StateFlows from RouteScanViewModel"
```

---

### Task 3: Render the minimap in ArRouteScreen

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt`

**Interfaces:**
- Consumes: `viewModel.fogCellCenters`/`viewModel.staleCellCenters` (Task 2), `viewModel.scannedCells` (existing, already collected at line 75), `viewModel.uiState.value.hotspots: List<ScanHotspot>` (existing field, `ScanHotspot.lat`/`.lng: Double`), `ScanMinimapOverlay` (existing, `composeApp/.../ui/components/ScanMinimapOverlay.kt`, unchanged), `GccMap` (existing map composable already used by `CameraCapture.kt`).

- [ ] **Step 1: Add the two new state collections**

After ArRouteScreen.kt:75 (`val scannedCells by viewModel.scannedCells.collectAsState()`), add:

```kotlin
    val fogCellCenters by viewModel.fogCellCenters.collectAsState()
    val staleCellCenters by viewModel.staleCellCenters.collectAsState()
```

- [ ] **Step 2: Add the imports the minimap box needs**

Add to the import block (alongside the existing `com.gcc.app.ui.components.*` imports at ArRouteScreen.kt:42-47):

```kotlin
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp
import com.gcc.app.ui.components.ScanMinimapOverlay
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.theme.MapStyles
import com.gcc.shared.model.Coordinates
```

(`GccMap`/`MapStyles` package paths — confirm the exact import path matches `CameraCapture.kt`'s own imports for these two symbols before applying; use whatever that file imports verbatim, since this plan's author read `CameraCapture.kt`'s usage but not its import block.)

- [ ] **Step 3: Render the minimap, bottom-start (the only free corner — top-start has the exit button, top-end has the points pill, bottom-end has "Back to Map")**

Add just before the closing `}` of the outer `Box(modifier = Modifier.fillMaxSize())` in ArRouteScreen.kt (i.e., immediately after the "Back to Map" box that ends around line 278, before `GoOutsideOverlay`):

```kotlin
        // Live minimap — fog (never scanned) / stale (re-visit) / this-session
        // scanned cells + hotspot pins, matching the spot-capture minimap.
        if (ui.currentLat != 0.0 || ui.currentLng != 0.0) {
            val miniMapWidth: Dp = 160.dp
            val miniMapHeight: Dp = miniMapWidth * 0.78f
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, bottom = 16.dp)
                    .size(width = miniMapWidth, height = miniMapHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.3f)),
            ) {
                GccMap(
                    modifier = Modifier.fillMaxSize(),
                    centerLat = ui.currentLat,
                    centerLng = ui.currentLng,
                    zoom = MapStyles.ZOOM_ACTIVE_JOB,
                    currentLocation = Coordinates(lat = ui.currentLat, lng = ui.currentLng),
                    gpsHeading = null,
                )
                ScanMinimapOverlay(
                    centerLat = ui.currentLat,
                    centerLng = ui.currentLng,
                    zoom = MapStyles.ZOOM_ACTIVE_JOB,
                    scanCellCenters = scannedCells.map { (row, col) -> viewModel.cellCenterLatLng(row, col) },
                    cellSizeMeters = 3.0,
                    trashItems = ui.hotspots.map { Coordinates(lat = it.lat, lng = it.lng) },
                    fogCellCenters = fogCellCenters,
                    staleCellCenters = staleCellCenters,
                )
            }
        }
```

(Fixed size rather than `CameraCapture`'s `BoxWithConstraints`-driven responsive width — this is a full-screen AR overlay, not a variable-width embedded camera card, so a fixed 160.dp is a deliberate simplification; revisit if it looks wrong on a tablet.)

- [ ] **Step 4: Build to confirm no compile errors**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Manual verification**

Run the desktop target (`./gradlew :composeApp:run` or whatever this repo's existing desktop-run task is — confirm via `./gradlew tasks --all | grep -i "composeApp:run"` if unsure) and start a route scan through to the AR screen; confirm the minimap renders bottom-start, showing gray fog / amber stale / green scanned cells and updating as you move (desktop target has no real GPS, so this may need the existing dev/simulated-location path this repo already has for desktop testing — check `LocationProvider`'s desktop actual for how routes are normally exercised without a real device).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt
git commit -m "Render live minimap on the AR route-scan screen"
```

---

## Self-Review Notes

- **Spec coverage**: Part A's spec intent ("wire ScanMinimapOverlay into ArRouteScreen, fed by fog/stale/scanned/trash data") is fully covered by Tasks 1-3. The spec's original wording ("add a fetchServerCoverage call... mirroring SpotScreen's pattern") is deliberately *not* followed literally — reading the actual code showed `RouteScanViewModel.seedFogFromServerCoverage()` already fetches this data for the points estimator; duplicating the fetch would violate the Global Constraint against a second network call. This plan derives the minimap data from what's already fetched instead, which better serves the spec's actual intent (a correct, efficient minimap) than its literal text.
- **Placeholder scan**: no TBD/TODO; the one explicit "confirm before applying" note (Task 3, Step 2) is a real, bounded uncertainty (an import path in a file the plan's research didn't fully read) rather than deferred design work — resolving it takes one grep, not a decision.
- **Type consistency**: `RouteFogClassification`/`classifyFogAndStale` (Task 1) → consumed with identical names in Task 2. `fogCellCenters`/`staleCellCenters: StateFlow<List<Pair<Double,Double>>>` (Task 2) → collected with identical names/types in Task 3. `ScanMinimapOverlay`'s param names (`scanCellCenters`, `cellSizeMeters`, `trashItems`, `fogCellCenters`, `staleCellCenters`) match its existing signature exactly (verified against `CameraCapture.kt`'s call site during research, not guessed).
