package com.gcc.app.platform

/**
 * The subset of a route-scan's not-yet-scanned cells the production coverage
 * ledger marks "stale" — scanned before, but long enough ago to need a
 * re-visit. Drives the minimap's amber "needs revisit" indicator.
 *
 * A cell absent from [cellPriorStates] was never scanned by anyone; a cell
 * present as "fresh" shouldn't appear in [unscannedCells] at all —
 * RouteScanViewModel folds fresh priors directly into CoverageTracker's
 * scanned-cell set before this function ever runs — and neither classifies
 * as stale here regardless, so the output is correct even on unexpected
 * input. (This used to also partition out a "fog" set for the deleted AR
 * fog-of-war renderer — the stale set is the only output anything consumes.)
 */
fun classifyStaleCells(
    unscannedCells: Set<Pair<Int, Int>>,
    cellPriorStates: Map<Pair<Int, Int>, String>,
): Set<Pair<Int, Int>> =
    unscannedCells.filterTo(mutableSetOf()) { cellPriorStates[it] == "stale" }
