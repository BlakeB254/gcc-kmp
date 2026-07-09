import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { cellCoverageSummary, worldObservations } from '@/db/schema'
import { and, between, eq, sql, type SQL } from 'drizzle-orm'
import { cellCenter } from '@/lib/world-observations'

export const dynamic = 'force-dynamic'

const STALE_AFTER_MS = 24 * 60 * 60 * 1000

// Rescan priority (P5): ageFactor x qualityBoost, computed at read time from
// existing summary columns only — no schema migration, no extra join.
//
// Dirty-observation weighting (nearby unresolved litter) was scoped for v1
// too but skipped as an unaffordable per-cell join at this table's scale
// (Global Constraints). Pillar 8 Task 4 supersedes that concern for the
// observed-only-litter case specifically: `cellCoverageSummary.locationCellId`
// and `worldObservations.locationCellId` are BOTH `gridCell()` ids (verified
// by reading scan-coverage.ts's recordCoverageEvent, which derives its
// cellId the same way world-observations.ts's recordWorldObservation does),
// so this is not a per-cell join — it's one extra bbox-scoped query building
// a JS Set, then a cheap set-membership check per row below. A broader
// dirty-observation-weighting join (beyond the observed-only-litter signal)
// remains deferred.
const PRIORITY_AGE_FULL_MS = 7 * 24 * 60 * 60 * 1000 // ageFactor saturates at 1.0 after 7d
const PRIORITY_QUALITY_BOOST: Record<string, number> = {
  // SEEN is even less "worked" than grazed (sight isn't work — Pillar 7
  // Global Constraints) — same max urgency boost as grazed.
  seen: 1.0,
  grazed: 1.0,
  close: 0.6,
}

export function computePriority(ageMs: number, bestQuality: string): number {
  const ageFactor = Math.min(1, ageMs / PRIORITY_AGE_FULL_MS)
  const qualityBoost = PRIORITY_QUALITY_BOOST[bestQuality] ?? PRIORITY_QUALITY_BOOST.grazed
  return ageFactor * qualityBoost
}

// Observed-only litter (Pillar 8 items 7-8): a cell with at least one
// current, dirty, observed-only (not-yet-diagnosed) world observation is a
// "needs completion" signal — boost its rescan priority so the
// needs-scanning lens surfaces it. Flat +0.25, clamped to 1.0. Extracted as
// a pure function (no DB access) so it's directly unit-testable.
const OBSERVED_ONLY_PRIORITY_BOOST = 0.25

export function applyObservedOnlyBoost(
  basePriority: number,
  cellId: string,
  observedOnlyCells: ReadonlySet<string>
): number {
  return Math.min(1, basePriority + (observedOnlyCells.has(cellId) ? OBSERVED_ONLY_PRIORITY_BOOST : 0))
}

/**
 * `domain = 'litter'` is load-bearing, not decorative. `state='dirty'` is set on
 * vegetation and construction observations too (a tree and a fence are both
 * "dirty" in the sense of "not a clean sweep"), so filtering on state alone
 * counts trees as trash. Prod carried 18 construction + 6 vegetation rows that
 * were inflating rescan priority through the observed-only boost below until
 * this filter was added.
 *
 * Exported as a pure condition builder (no DB access, no execution) so the
 * `domain` predicate can be pinned by a test via `.toSQL()` — same
 * side-effect-free pattern as computePriority/applyObservedOnlyBoost.
 */
export function currentDirtyLitterInBbox(
  minLng: number,
  minLat: number,
  maxLng: number,
  maxLat: number
): SQL | undefined {
  return and(
    eq(worldObservations.isCurrent, true),
    eq(worldObservations.domain, 'litter'),
    eq(worldObservations.state, 'dirty'),
    between(worldObservations.lng, minLng, maxLng),
    between(worldObservations.lat, minLat, maxLat)
  )
}

/**
 * The observed-only signal narrows [currentDirtyLitterInBbox] to litter that has
 * NOT yet been close-range diagnosed — a "needs completion" hint for the
 * rescan-priority boost. Diagnosed litter is already well characterised and
 * should not re-boost priority.
 */
export function observedOnlyLitterInBbox(
  minLng: number,
  minLat: number,
  maxLng: number,
  maxLat: number
): SQL | undefined {
  return and(
    currentDirtyLitterInBbox(minLng, minLat, maxLng, maxLat),
    eq(worldObservations.diagnosticStatus, 'observed')
  )
}

/** Per-cell litter rollup fed to the client's problem-area blobs. */
export interface LitterCellStats {
  litterCount: number
  litterWeightGrams: number
}

/**
 * Merge the bbox litter rollup onto a coverage cell. A cell with observations
 * but no recorded weight yields `litterWeightGrams: 0` with a non-zero count —
 * the client tiers on count in that case rather than mis-reading it as "light".
 */
export function litterStatsFor(
  cellId: string,
  stats: ReadonlyMap<string, LitterCellStats>
): LitterCellStats {
  return stats.get(cellId) ?? { litterCount: 0, litterWeightGrams: 0 }
}

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const bboxParam = searchParams.get('bbox')
    if (!bboxParam) {
      return NextResponse.json({ cells: [], success: false, error: 'bbox required' }, { status: 400 })
    }

    const parts = bboxParam.split(',').map(Number)
    if (parts.length !== 4 || !parts.every(Number.isFinite)) {
      return NextResponse.json({ cells: [], success: false, error: 'bbox must be minLng,minLat,maxLng,maxLat' }, { status: 400 })
    }
    const [minLng, minLat, maxLng, maxLat] = parts

    // Optional delta cursor: unparseable/missing `since` is silently ignored
    // (full response) so old/broken clients never see a 4xx here.
    const sinceParam = searchParams.get('since')
    const sinceMs = sinceParam ? new Date(sinceParam).getTime() : NaN
    const since = Number.isFinite(sinceMs) ? sinceMs : null

    const rows = await db
      .select()
      .from(cellCoverageSummary)
      .limit(5000)

    // Observed-only litter cells (Pillar 8 Task 4) — bbox-scoped to match
    // this endpoint's own scoping (see the boost's doc comment above for
    // why this is a cheap Set-membership pass, not a per-cell join).
    // worldObservations has real lat/lng columns (unlike cellCoverageSummary,
    // which only has a derived cellCenter()), so this filters at the SQL
    // level rather than fetch-all-then-filter-in-JS.
    const observedOnlyRows = await db
      .selectDistinct({ locationCellId: worldObservations.locationCellId })
      .from(worldObservations)
      .where(observedOnlyLitterInBbox(minLng, minLat, maxLng, maxLat))
      .limit(5000)
    const observedOnlyCells = new Set(observedOnlyRows.map((r) => r.locationCellId))

    // Per-cell litter rollup — one bbox-scoped GROUP BY, same cheap-Set
    // reasoning as observedOnlyCells above (locationCellId is the shared
    // gridCell() id on both tables). Feeds the client's problem-area blobs:
    // hue by severity, so the client needs both a count and a mass.
    // `attributes->>'estimatedWeightGrams'` is the real key (the schema's
    // `estimatedWeightLbs` comment is stale); NULLIF guards rows whose
    // attributes exist but carry no weight.
    const litterRows = await db
      .select({
        locationCellId: worldObservations.locationCellId,
        litterCount: sql<number>`count(*)::int`,
        litterWeightGrams: sql<number>`coalesce(sum(nullif(${worldObservations.attributes} ->> 'estimatedWeightGrams', '')::numeric), 0)::float8`,
      })
      .from(worldObservations)
      .where(currentDirtyLitterInBbox(minLng, minLat, maxLng, maxLat))
      .groupBy(worldObservations.locationCellId)
      .limit(5000)
    const litterStats = new Map<string, LitterCellStats>(
      litterRows.map((r) => [
        r.locationCellId,
        { litterCount: Number(r.litterCount), litterWeightGrams: Number(r.litterWeightGrams) },
      ])
    )

    const now = Date.now()
    const cells = rows
      .map((r) => {
        const center = cellCenter(r.locationCellId)
        return { r, center }
      })
      .filter(({ center }) =>
        center.lng >= minLng && center.lng <= maxLng &&
        center.lat >= minLat && center.lat <= maxLat
      )
      .filter(({ r }) => since === null || new Date(r.lastScannedAt).getTime() >= since)
      .map(({ r, center }) => {
        const ageMs = now - new Date(r.lastScannedAt).getTime()
        const litter = litterStatsFor(r.locationCellId, litterStats)
        return {
          locationCellId: r.locationCellId,
          lat: center.lat,
          lng: center.lng,
          state: (ageMs > STALE_AFTER_MS ? 'stale' : 'fresh') as 'fresh' | 'stale',
          quality: r.bestQuality,
          lastScannedAt: r.lastScannedAt,
          totalDistanceMeters: r.totalDistanceMeters,
          priority: applyObservedOnlyBoost(computePriority(ageMs, r.bestQuality), r.locationCellId, observedOnlyCells),
          // Additive (default 0 client-side): a pre-deploy client simply keeps
          // its old spot-only problem derivation rather than breaking.
          litterCount: litter.litterCount,
          litterWeightGrams: litter.litterWeightGrams,
        }
      })

    return NextResponse.json({ cells, success: true })
  } catch (error) {
    console.error('GET /api/map/coverage error:', error)
    return NextResponse.json({ cells: [], success: false }, { status: 500 })
  }
}
