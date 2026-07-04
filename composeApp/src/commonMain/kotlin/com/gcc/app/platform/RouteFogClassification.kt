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
