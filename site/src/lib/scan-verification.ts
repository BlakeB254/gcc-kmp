/**
 * Server-side verification of a block-route scan (audit finding H2).
 *
 * Before this, `POST /api/scans/{id}/complete` accepted an empty body from any
 * phase, the lap-one proof was skipped whenever the client omitted it, and a
 * job completed on the strength of *some* completed scan. Everything the
 * server knew about coverage came from the client's own `coveragePercent`.
 *
 * Now a scan completes only when:
 *   1. it is still `in_progress` and in the cleaning lap (`cleaning`, or the
 *      `submitting` step that can only follow it — see SCAN_PHASE_ORDER in
 *      mobile-route-scan-response.ts);
 *   2. lap one was verified at the scouting → scout_review boundary
 *      (`route_scans.lap_one_verified_at`, stamped by the phase route only
 *      when the same server-computed coverage reached
 *      LAP_ONE_MIN_ROUTE_COVERAGE); and
 *   3. the route coverage the SERVER computes from the coverage events
 *      attributed to this scan at ingest (`scan_coverage_events.route_scan_id`,
 *      stamped by POST /api/scans/coverage — never sent by the client) (`scan_coverage_events`, see docs/governance/
 *      scan-and-coverage-model.md §8) meets SCAN_COMPLETION_MIN_ROUTE_COVERAGE.
 *
 * Pure module — no `@/db` import — so `tsx --test` loads it without a
 * DATABASE_URL. The DB reads live in lib/scan-completion.ts.
 */
import { gridCell } from '@/lib/grid-cell-client'
import { METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from '@/lib/geo-utils'

/**
 * Fraction of the route's cells this scan must have worked (GRAZED or CLOSE).
 * Neither the scan-and-coverage doc nor feature-spec-2026-05 states a server
 * threshold; the client's lap-1 gate is 95% of ITS corridor on a 3m lattice
 * (RouteScanViewModel.LAP_COMPLETE_PERCENT). The server measures on the
 * coarser 10m gridCell lattice from GPS fixes alone, so it uses 0.9 rather
 * than claim the client's precision.
 */
export const SCAN_COMPLETION_MIN_ROUTE_COVERAGE = 0.9

/**
 * A route cell counts as worked when a worked cell's centre lies within this
 * distance of it. Mirrors the client corridor half-width
 * (CoverageTracker.corridorWidthMeters = 15): a worker on the sidewalk is
 * still working a route drawn down the street. On the 10m lattice this is
 * exactly the cell and its 8 neighbours.
 */
export const ROUTE_COVERAGE_TOLERANCE_METERS = 15

/** Spacing of the points sampled along the route line to find its cells. */
const ROUTE_SAMPLE_STEP_METERS = 2

/** The 10m gridCell size (world-observations.ts CELL_SIZE_METERS). */
const CELL_METERS = 10

/** Phases a scan may complete from: the cleaning lap, or its submit step. */
export const COMPLETABLE_SCAN_PHASES = ['cleaning', 'submitting'] as const

/** Coverage-event qualities that count as worked ground (SEEN never does). */
export const WORKED_COVERAGE_QUALITIES = ['grazed', 'close'] as const

type LngLat = [number, number]

function isLngLat(value: unknown): value is LngLat {
  return (
    Array.isArray(value) &&
    value.length >= 2 &&
    Number.isFinite(Number(value[0])) &&
    Number.isFinite(Number(value[1]))
  )
}

/**
 * The polylines in a route's `geometry` jsonb. Accepts a GeoJSON LineString,
 * MultiLineString, Polygon or MultiPolygon (rings walked as lines), a Feature
 * wrapping one, or a bare `[lng, lat][]`. Anything else yields no lines.
 */
export function routeLines(geometry: unknown): LngLat[][] {
  if (Array.isArray(geometry)) {
    const line = geometry.filter(isLngLat).map((p) => [Number(p[0]), Number(p[1])] as LngLat)
    return line.length > 0 ? [line] : []
  }
  if (!geometry || typeof geometry !== 'object') return []
  const g = geometry as { type?: unknown; coordinates?: unknown; geometry?: unknown }
  if (g.type === 'Feature') return routeLines(g.geometry)
  const coords = g.coordinates
  switch (g.type) {
    case 'LineString':
      return routeLines(coords)
    case 'MultiLineString':
    case 'Polygon':
      return Array.isArray(coords) ? coords.flatMap((line) => routeLines(line)) : []
    case 'MultiPolygon':
      return Array.isArray(coords)
        ? coords.flatMap((poly) => (Array.isArray(poly) ? poly.flatMap((ring) => routeLines(ring)) : []))
        : []
    default:
      return []
  }
}

/**
 * Every 10m gridCell the route passes through, found by sampling each segment
 * every ROUTE_SAMPLE_STEP_METERS. Uses the same frozen projection constants as
 * gridCell (docs/CONVENTIONS.md §2), never cos(lat).
 */
export function routeRequiredCells(geometry: unknown): Set<string> {
  const cells = new Set<string>()
  for (const line of routeLines(geometry)) {
    if (line.length === 1) {
      cells.add(gridCell(line[0][1], line[0][0]))
      continue
    }
    for (let i = 1; i < line.length; i++) {
      const [lng1, lat1] = line[i - 1]
      const [lng2, lat2] = line[i]
      const meters = Math.hypot((lng2 - lng1) * METERS_PER_DEG_LNG, (lat2 - lat1) * METERS_PER_DEG_LAT)
      const steps = Math.max(1, Math.ceil(meters / ROUTE_SAMPLE_STEP_METERS))
      for (let s = 0; s <= steps; s++) {
        const t = s / steps
        cells.add(gridCell(lat1 + (lat2 - lat1) * t, lng1 + (lng2 - lng1) * t))
      }
    }
  }
  return cells
}

function parseCell(cellId: string): [number, number] | null {
  const [a, b] = cellId.split(':')
  const lat = Number(a)
  const lng = Number(b)
  return Number.isInteger(lat) && Number.isInteger(lng) ? [lat, lng] : null
}

/** Index offsets whose cell centres lie within ROUTE_COVERAGE_TOLERANCE_METERS. */
const TOLERANCE_OFFSETS: Array<[number, number]> = (() => {
  const reach = Math.floor(ROUTE_COVERAGE_TOLERANCE_METERS / CELL_METERS)
  const out: Array<[number, number]> = []
  for (let dLat = -reach; dLat <= reach; dLat++) {
    for (let dLng = -reach; dLng <= reach; dLng++) {
      if (Math.hypot(dLat * CELL_METERS, dLng * CELL_METERS) <= ROUTE_COVERAGE_TOLERANCE_METERS) {
        out.push([dLat, dLng])
      }
    }
  }
  return out
})()

/** The cell plus every cell whose centre is within the tolerance of it. */
export function cellsWithinTolerance(cellId: string): string[] {
  const parsed = parseCell(cellId)
  if (!parsed) return []
  const [lat, lng] = parsed
  return TOLERANCE_OFFSETS.map(([dLat, dLng]) => `${lat + dLat}:${lng + dLng}`)
}

export type RouteCoverage = {
  /** Route cells. */
  required: number
  /** Route cells with a worked cell within tolerance. */
  covered: number
  /** covered / required; 0 when the route has no cells. */
  ratio: number
}

/** How much of the route this scan's worked cells cover. */
export function computeRouteCoverage(requiredCells: Set<string>, workedCells: Set<string>): RouteCoverage {
  let covered = 0
  for (const cell of requiredCells) {
    if (cellsWithinTolerance(cell).some((c) => workedCells.has(c))) covered++
  }
  const required = requiredCells.size
  return { required, covered, ratio: required > 0 ? covered / required : 0 }
}

export function meetsCoverageThreshold(coverage: RouteCoverage): boolean {
  return coverage.required > 0 && coverage.ratio >= SCAN_COMPLETION_MIN_ROUTE_COVERAGE
}

export type ScanRefusal = { status: 409; message: string }

/**
 * Server-side route coverage a scan must reach at the scouting → scout_review
 * boundary before lap one is stamped verified. The scan-and-coverage doc gives
 * no separate server lap-one figure (its 95% is the CLIENT corridor on a 3m
 * lattice), so this is the same 10m-lattice threshold completion uses: lap one
 * is the full baseline lap, so it must already cover what completion needs.
 */
export const LAP_ONE_MIN_ROUTE_COVERAGE = SCAN_COMPLETION_MIN_ROUTE_COVERAGE

export type LapOneDecision = 'not-boundary' | 'verified' | 'refused-proof' | 'refused-coverage'

/**
 * The lap-one gate on a phase change. Only scouting → scout_review is gated.
 *
 * `proof` is validateLapOneProof's result (mobile-route-scan-response.ts):
 * true = valid, false = failing, null = the client sent no proof. It is only
 * a consistency check — those are numbers the client chose. A missing proof
 * is refused exactly like a failing one.
 *
 * `coverage` is what decides: the route coverage the SERVER computed from
 * the coverage events attributed to this scan (scan_coverage_events.
 * route_scan_id). Null (not computed) is refused.
 */
export function lapOneGate(
  fromPhase: string,
  toPhase: string,
  proof: boolean | null,
  coverage: RouteCoverage | null,
): LapOneDecision {
  if (fromPhase !== 'scouting' || toPhase !== 'scout_review') return 'not-boundary'
  if (proof !== true) return 'refused-proof'
  if (!coverage || coverage.required === 0 || coverage.ratio < LAP_ONE_MIN_ROUTE_COVERAGE) return 'refused-coverage'
  return 'verified'
}

export function lapOneCoverageMessage(coverage: RouteCoverage | null): string {
  if (!coverage || coverage.required === 0) return 'This route has no geometry to verify lap 1 against'
  const pct = Math.floor(coverage.ratio * 100)
  const need = Math.round(LAP_ONE_MIN_ROUTE_COVERAGE * 100)
  return (
    `Lap 1 is incomplete: the server has recorded ${pct}% of this route as scanned; ${need}% is required. ` +
    'If you just finished, wait a few seconds for your scan to upload, then try again.'
  )
}

// ── Coverage attribution ───────────────────────────────────────────────────

/**
 * A route scan still `in_progress` this long after it started is abandoned
 * (the cron sweep marks it so — lib/paid-job-sweep-db.ts). Until the sweep
 * runs, such a scan is also not "active": it neither blocks a new scan nor
 * receives coverage events.
 */
export const ROUTE_SCAN_ABANDON_DAYS = 7

export type ActiveScanForAttribution = { id: number; scoutStartedAt: string }

function msOf(ts: string | null | undefined): number | null {
  if (!ts) return null
  const t = Date.parse(ts)
  return Number.isNaN(t) ? null : t
}

/**
 * The route scan a coverage event belongs to (scan_coverage_events.
 * route_scan_id), or null. `active` is the caller's single active route scan
 * (lib/scan-completion.ts findCoverageAttributionScan). Only route-mode events
 * captured at/after that scan's start are attributed; spot-mode events, events
 * from before the scan, and events with no active scan stay unattributed and
 * can never count toward a scan's verification.
 */
export function attributeCoverageEvent(
  event: { scanMode: string; capturedAt: string },
  active: ActiveScanForAttribution | null,
): number | null {
  if (!active || event.scanMode !== 'route') return null
  const captured = msOf(event.capturedAt)
  const started = msOf(active.scoutStartedAt)
  if (captured == null || started == null || captured < started) return null
  return active.id
}

export type ScanForCompletion = {
  phase: string | null
  status: string | null
  lapOneVerifiedAt: string | null
}

export function isScanAlreadyComplete(scan: Pick<ScanForCompletion, 'phase' | 'status'>): boolean {
  return scan.status === 'completed' && scan.phase === 'complete'
}

/**
 * The state-machine half of the completion gate (coverage is checked after,
 * because it needs a DB read). Null means the scan may proceed to coverage.
 * An already-complete scan is not handled here — the route answers it
 * idempotently so a retried submit does not error.
 */
export function scanCompletionRefusal(scan: ScanForCompletion): ScanRefusal | null {
  if (scan.status !== 'in_progress') {
    return { status: 409, message: `Scan is ${scan.status ?? 'in an unknown state'} and cannot be completed` }
  }
  if (!(COMPLETABLE_SCAN_PHASES as readonly string[]).includes(scan.phase ?? '')) {
    return {
      status: 409,
      message: `A scan can only be submitted from the cleaning lap (current phase: ${scan.phase ?? 'unknown'})`,
    }
  }
  if (!scan.lapOneVerifiedAt) {
    return { status: 409, message: 'Lap 1 proof was never recorded for this scan — the baseline lap must be completed first' }
  }
  return null
}

export function coverageRefusal(coverage: RouteCoverage): ScanRefusal | null {
  if (coverage.required === 0) {
    return { status: 409, message: 'This route has no geometry to verify the scan against' }
  }
  if (!meetsCoverageThreshold(coverage)) {
    const pct = Math.floor(coverage.ratio * 100)
    const need = Math.round(SCAN_COMPLETION_MIN_ROUTE_COVERAGE * 100)
    return {
      status: 409,
      message: `The server recorded ${pct}% of this route as scanned; ${need}% is required. Walk the remaining part of the route, then submit again.`,
    }
  }
  return null
}

/**
 * Cells a completed scan may use to supersede community reports and stale
 * observations: the client's `coveredCellIds` are only a hint, narrowed to
 * cells the server itself recorded this scan working AND that lie on (within
 * tolerance of) the route. Reports elsewhere — someone else's block, or a
 * cell the client merely claimed — are never touched.
 */
export function supersedableCells(
  clientCellIds: Iterable<string>,
  workedCells: Set<string>,
  requiredCells: Set<string>,
): string[] {
  const nearRoute = new Set<string>()
  for (const cell of requiredCells) for (const c of cellsWithinTolerance(cell)) nearRoute.add(c)
  return [...new Set(clientCellIds)].filter((c) => workedCells.has(c) && nearRoute.has(c))
}

// ── Job completion ─────────────────────────────────────────────────────────

export type ScanForJob = {
  id: number
  status: string | null
  phase: string | null
  verifiedCoverageRatio: number | null
  createdAt: string
  /** Id of a job (other than this one) that already used this scan, if any. */
  usedByJobId: number | null
}

export type JobForScan = {
  id: number
  claimedAt: string | null
  startedAt: string | null
}

function ms(ts: string | null | undefined): number | null {
  if (!ts) return null
  const t = Date.parse(ts)
  return Number.isNaN(t) ? null : t
}

/**
 * Whether a scan (already filtered to this job's route + worker) can be the
 * evidence for completing this job: server-verified, created at/after the
 * worker claimed the job (falling back to start), and not already spent on
 * a different job.
 */
export function scanQualifiesForJob(scan: ScanForJob, job: JobForScan): boolean {
  if (scan.status !== 'completed' || scan.phase !== 'complete') return false
  if (scan.verifiedCoverageRatio == null || scan.verifiedCoverageRatio < SCAN_COMPLETION_MIN_ROUTE_COVERAGE) return false
  const since = ms(job.claimedAt) ?? ms(job.startedAt)
  const created = ms(scan.createdAt)
  if (since == null || created == null || created < since) return false
  if (scan.usedByJobId != null && scan.usedByJobId !== job.id) return false
  return true
}

/** The first qualifying scan (callers pass candidates newest-first). */
export function pickQualifyingScan(scans: ScanForJob[], job: JobForScan): ScanForJob | null {
  return scans.find((s) => scanQualifiesForJob(s, job)) ?? null
}

