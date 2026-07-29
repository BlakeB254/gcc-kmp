import { db } from '@/db'
import { worldObservations, blockExclusionZones, communityReports } from '@/db/schema'
import { eq, and, isNull, inArray, ne } from 'drizzle-orm'
import { pointInPolygon, METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from '@/lib/geo-utils'

const CELL_SIZE_METERS = 10

/**
 * Deterministic ~10m grid cell key for a lat/lng, used to group observations
 * of "the same physical spot" across time and across source type.
 */
export function gridCell(lat: number, lng: number, cellSizeMeters = CELL_SIZE_METERS): string {
  const latStep = cellSizeMeters / METERS_PER_DEG_LAT
  const lngStep = cellSizeMeters / METERS_PER_DEG_LNG
  const latIdx = Math.floor(lat / latStep)
  const lngIdx = Math.floor(lng / lngStep)
  return `${latIdx}:${lngIdx}`
}

/**
 * Inverse of gridCell: approximate lat/lng center of a cell, given its key.
 * Used by map-facing reads that need real coordinates back, not just a cell id.
 */
export function cellCenter(cellId: string, cellSizeMeters = CELL_SIZE_METERS): { lat: number; lng: number } {
  const [latIdxStr, lngIdxStr] = cellId.split(':')
  const latIdx = Number(latIdxStr)
  const lngIdx = Number(lngIdxStr)
  const latStep = cellSizeMeters / METERS_PER_DEG_LAT
  const lngStep = cellSizeMeters / METERS_PER_DEG_LNG
  return {
    lat: (latIdx + 0.5) * latStep,
    lng: (lngIdx + 0.5) * lngStep,
  }
}

export interface RecordObservationInput {
  sourceType: 'route_scan' | 'spot_report' | 'ar_auto_detect'
  scanHotspotId?: number
  communityReportId?: number
  lat: number
  lng: number
  itemType: string | null
  state: 'dirty' | 'clean'
  // Which world domain this observation belongs to. Defaults to 'litter';
  // vegetation/construction/land_use are auto-registered landmark elements.
  domain?: 'litter' | 'vegetation' | 'construction' | 'land_use'
  // Domain-specific descriptive payload — litter's severity/volume/weight
  // today, whatever a future domain needs tomorrow, without a schema change.
  attributes?: Record<string, unknown>
  confidence?: number
  mediaUrl?: string
  depthData?: string
  arAnchorData?: string
  capturedAt: string
  blockId?: number
}

/**
 * Merges a prior observation's attributes forward into a new observation's
 * attributes — new keys win over inherited ones, matching the supersede
 * comment in `recordWorldObservation`. Pure/side-effect-free (no DB access)
 * so the litter-truth completion-upgrade and diagnosis-persistence
 * semantics (see `computeDiagnosticStatus`) are unit-testable without a
 * database, the same pattern `selectReportsInStaleCells` uses below.
 */
export function mergeObservationAttributes(
  prior: Record<string, unknown> | null | undefined,
  incoming: Record<string, unknown> | null | undefined
): Record<string, unknown> | undefined {
  if (prior && typeof prior === 'object') {
    return { ...prior, ...(incoming ?? {}) }
  }
  return incoming ?? undefined
}

/**
 * Supersede policy for an observation's evidence photo, mirroring
 * `mergeObservationAttributes`: the incoming photo wins when present, but a
 * photoLESS re-file (a submit whose photo upload failed, an old client, a
 * lean auto re-scan) inherits the prior current row's photo instead of
 * erasing it. Evidence — like a diagnosis — is a fact about the litter, not
 * a property of the latest scan. Pure, so the policy is unit-testable.
 */
export function mergeObservationMediaUrl(
  prior: string | null | undefined,
  incoming: string | null | undefined
): string | undefined {
  return incoming ?? prior ?? undefined
}

/**
 * Litter-truth lifecycle (Pillar 8 items 7-8): determine 'diagnosed' vs
 * 'observed' from an observation's attributes. OBSERVED = identified from
 * afar (distant detection photo + position/type), no close capture.
 * DIAGNOSED = a close-range capture upgraded it — `estimatedWeightGrams`
 * AND `estimatedWidthMeters` together (a close confirm measured both), OR
 * any `objectCapture*` attribute (a full photogrammetry capture, always
 * close-range by construction). Anything else is 'observed'.
 *
 * CRITICAL: callers MUST pass the POST-MERGE attributes (i.e. after
 * `mergeObservationAttributes` has folded the prior current row's
 * attributes forward), not the raw per-request input. Computing from the
 * merged attributes — rather than from the new request alone — gives the
 * completion-upgrade semantics for free in both directions:
 *  (a) prior observed-only row (no size attrs) + new close-confirm (weight
 *      + width) in the same cell+itemType -> the merge folds the new
 *      weight+width in -> the new current row is 'diagnosed' (upgrade ✓).
 *  (b) prior diagnosed row (weight+width already recorded) + a new LEAN
 *      re-scan (auto-detect, no size data) -> the merge retains the prior
 *      weight+width (new keys win, but the re-scan supplies none) -> the
 *      new current row STAYS 'diagnosed'. This is deliberate: diagnosis is
 *      a fact about the litter itself (it WAS close-range measured, at
 *      some point), not a property of the latest scan's instrument — a
 *      later drive-by glance must not un-diagnose it.
 */
export function computeDiagnosticStatus(
  attributes: Record<string, unknown> | null | undefined
): 'observed' | 'diagnosed' {
  if (!attributes) return 'observed'
  const weight = attributes.estimatedWeightGrams
  const width = attributes.estimatedWidthMeters
  const hasWeightAndWidth =
    typeof weight === 'number' && Number.isFinite(weight) &&
    typeof width === 'number' && Number.isFinite(width)
  const hasObjectCapture = Object.keys(attributes).some((key) => key.startsWith('objectCapture'))
  return hasWeightAndWidth || hasObjectCapture ? 'diagnosed' : 'observed'
}

/**
 * Record a new observation, superseding whatever was previously "current"
 * at the same physical spot. Nothing is ever deleted or updated in place
 * except the isCurrent flag — every insert is a new immutable row, so the
 * full chronological history (dirty -> clean -> dirty) survives per cell.
 */
export async function recordWorldObservation(input: RecordObservationInput) {
  const cellId = gridCell(input.lat, input.lng)

  const excluded = input.blockId != null
    ? await isWithinExclusionZone(input.blockId, input.lat, input.lng)
    : false

  if (input.state === 'clean') {
    // A clean re-scan supersedes ALL currently-dirty items in this cell,
    // regardless of their itemType — a clean scan means "nothing dirty here now."
    const superseded = await db
      .select({ communityReportId: worldObservations.communityReportId })
      .from(worldObservations)
      .where(and(
        eq(worldObservations.locationCellId, cellId),
        eq(worldObservations.state, 'dirty'),
        eq(worldObservations.isCurrent, true)
      ))
    await db
      .update(worldObservations)
      .set({ isCurrent: false })
      .where(and(
        eq(worldObservations.locationCellId, cellId),
        eq(worldObservations.state, 'dirty'),
        eq(worldObservations.isCurrent, true)
      ))
    await resolveSupersededReports(superseded.map((row) => row.communityReportId))
  } else {
    // A new dirty observation of the same itemType in the same cell supersedes
    // only the prior observation of that itemType (a couch and glass 1m apart
    // in the same cell are both allowed to be concurrently "current").
    // itemType may legitimately be NULL, and NULL = '' never matches in SQL,
    // so branch to IS NULL rather than coercing null to an empty string.
    const itemTypeCondition = input.itemType === null
      ? isNull(worldObservations.itemType)
      : eq(worldObservations.itemType, input.itemType)

    // Supersede MERGES attributes forward, not replace: a lean re-scan (auto
    // detect with no size data) must not wipe a prior observation's measured
    // estimates (weight/width/objectCapture). New keys win over inherited.
    const [prior] = await db
      .select({ attributes: worldObservations.attributes, mediaUrl: worldObservations.mediaUrl })
      .from(worldObservations)
      .where(and(
        eq(worldObservations.locationCellId, cellId),
        itemTypeCondition,
        eq(worldObservations.isCurrent, true)
      ))
      .limit(1)
    input = {
      ...input,
      attributes: mergeObservationAttributes(
        prior?.attributes as Record<string, unknown> | null | undefined,
        input.attributes
      ),
      // mediaUrl merges forward the same way: a photoless re-file (submit
      // whose photo upload failed, or a pre-per-item-photo client) must not
      // replace a row that HAS evidence with one that doesn't — that exact
      // path was deleting mid-scan marker photos from the map at submit.
      mediaUrl: mergeObservationMediaUrl(prior?.mediaUrl, input.mediaUrl),
    }

    await db
      .update(worldObservations)
      .set({ isCurrent: false })
      .where(and(
        eq(worldObservations.locationCellId, cellId),
        itemTypeCondition,
        eq(worldObservations.isCurrent, true)
      ))
  }

  // Computed from input.attributes AFTER the dirty-path merge above (see
  // computeDiagnosticStatus's CRITICAL note) — this placement is what gives
  // both upgrade and persistence semantics. The insert sets this
  // explicitly rather than relying on the DB default: schema.ts now names
  // the column, so drizzle includes it in every generated INSERT
  // regardless (MERGE ACTION: migration 0029 adds this column — see that
  // file's header).
  const diagnosticStatus = computeDiagnosticStatus(input.attributes)

  const [row] = await db
    .insert(worldObservations)
    .values({
      scanHotspotId: input.scanHotspotId ?? null,
      communityReportId: input.communityReportId ?? null,
      sourceType: input.sourceType,
      lat: input.lat,
      lng: input.lng,
      locationCellId: cellId,
      itemType: input.itemType,
      state: input.state,
      domain: input.domain ?? 'litter',
      diagnosticStatus,
      attributes: input.attributes ?? null,
      confidence: input.confidence ?? null,
      mediaUrl: input.mediaUrl ?? null,
      depthData: input.depthData ?? null,
      arAnchorData: input.arAnchorData ?? null,
      capturedAt: input.capturedAt,
      isCurrent: true,
      excludedPrivateProperty: excluded,
    })
    .returning()

  return row
}

/**
 * When a clean observation supersedes prior dirty rows, any of those rows
 * that trace back to a `communityReports` submission need that report
 * resolved too — otherwise the legacy report keeps showing on any map
 * surface that still reads `communityReports` directly (both current map
 * implementations do, for their primary marker layer) even though the
 * canonical `worldObservations` record correctly shows it superseded.
 * Only 'pending'/'verified' reports are touched — 'rejected' is a moderator
 * call this shouldn't silently overwrite, and 'resolved'/'acknowledged' are
 * already settled.
 */
async function resolveSupersededReports(communityReportIds: (number | null)[]) {
  const ids = [...new Set(communityReportIds.filter((id): id is number => id != null))]
  if (ids.length === 0) return
  await db
    .update(communityReports)
    .set({ status: 'resolved', showOnMap: false })
    .where(and(
      inArray(communityReports.id, ids),
      inArray(communityReports.status, ['pending', 'verified'])
    ))
}

/** Shape a `coveredCellIds` entry must match to be treated as a `gridCell()` id — same `${latIdx}:${lngIdx}` shape gridCell() produces. */
const CELL_ID_PATTERN = /^-?\d+:-?\d+$/

/**
 * Cap on how many `coveredCellIds` a single scan submission can carry. A
 * scan session's coverage tracker could in principle accumulate far more
 * cells than any one report needs to sweep; this bounds the per-request
 * work (both the truncation itself and the downstream community-reports
 * full-table scan in `resolveByCoveredCells`) without rejecting the
 * request — oversized input is silently truncated to the first N entries,
 * not treated as an error.
 */
const MAX_COVERED_CELL_IDS = 2000

/**
 * Validate + normalize the optional `coveredCellIds` field on a scan
 * submission (POST /api/spots). Old clients never send this field, so any
 * non-array value (including `undefined`/`null`) is treated as "no extra
 * cells to sweep" rather than a validation error — this is what keeps old
 * clients wire-compatible: they get exactly the legacy point-radius
 * supersede behavior and nothing else. Entries that aren't strings, or
 * don't match the gridCell id shape, are dropped rather than failing the
 * whole request.
 */
export function parseCoveredCellIds(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  return raw
    .filter((v): v is string => typeof v === 'string' && CELL_ID_PATTERN.test(v))
    .slice(0, MAX_COVERED_CELL_IDS)
}

/**
 * Pure decision function: given raw community-report candidates (id +
 * whatever shape `coordinates` happens to be — object `{lat,lng}` or array
 * `[lng,lat]`, the same dual-shape handling the GET handler in
 * `api/spots/route.ts` already does for this same column) and a set of
 * stale cell ids, returns the ids of the candidates whose `gridCell()`
 * falls inside `staleCells`. Kept side-effect-free (no DB access) so the
 * cell-matching logic is unit-testable without a database.
 */
export function selectReportsInStaleCells(
  candidates: { id: number; coordinates: unknown }[],
  staleCells: Iterable<string>
): number[] {
  const staleCellSet = staleCells instanceof Set ? staleCells : new Set(staleCells)
  const matched: number[] = []
  for (const candidate of candidates) {
    const coords = candidate.coordinates as any
    let lat: number | undefined
    let lng: number | undefined
    if (Array.isArray(coords)) {
      lng = Number(coords[0])
      lat = Number(coords[1])
    } else if (coords && typeof coords === 'object') {
      lat = Number(coords.lat)
      lng = Number(coords.lng ?? coords.lon) // .lon fallback mirrors spots/route.ts, _derived.ts, data-export/route.ts
    }
    if (Number.isFinite(lat) && Number.isFinite(lng) && staleCellSet.has(gridCell(lat as number, lng as number))) {
      matched.push(candidate.id)
    }
  }
  return matched
}

export interface CellSupersedeResult {
  resolvedReportIds: number[]
  invalidatedObservationCount: number
}

/**
 * Broader "the scan swept this cell" supersede pass — a second, independent
 * supersede driven by the full list of cells a scan's coverage tracker
 * actually swept (GRAZED+ quality tier), not just the point-radius supersede
 * around the scan's anchor + detected items (that legacy behavior in
 * `api/spots/route.ts` is unchanged). `staleCells` are cells the scan
 * covered but did NOT re-confirm this submission (no anchor/detected item
 * landed there) — the caller is responsible for excluding re-confirmed
 * cells before calling this. A covered-but-not-reconfirmed cell means the
 * scan swept it and found nothing there: any pending/verified community
 * report there is resolved, and any currently-current dirty world
 * observation there is marked non-current.
 *
 * - Community reports: `coordinates` is untyped jsonb (object or array
 *   shape depending on submission source), so `gridCell()` can't be
 *   expressed as a SQL predicate. This table is small — the same scale as
 *   the other near-full-table scans already in this file/route — so
 *   candidates are fetched in bulk and filtered in JS via
 *   `selectReportsInStaleCells`, favoring correctness/simplicity over a
 *   fragile SQL json-path expression.
 * - World observations: `locationCellId` is a real stored column, so this
 *   half is a direct SQL `inArray` filter — no JS filtering needed.
 *
 * `dbClient` is an injectable seam for unit tests (defaults to the real
 * `db`); production callers should never pass it.
 *
 * `excludeReportId` is optional (Amendment 2 / Task 1b): scan submissions
 * no longer insert a `communityReports` row, so there's nothing to exclude
 * from the sweep — callers on that path pass `undefined` and every
 * pending/verified report becomes an eligible candidate.
 */
export async function resolveByCoveredCells(
  staleCells: string[],
  excludeReportId: number | undefined,
  reason: string,
  dbClient: typeof db = db
): Promise<CellSupersedeResult> {
  if (staleCells.length === 0) {
    return { resolvedReportIds: [], invalidatedObservationCount: 0 }
  }

  const candidates = await dbClient
    .select({ id: communityReports.id, coordinates: communityReports.coordinates })
    .from(communityReports)
    .where(and(
      inArray(communityReports.status, ['pending', 'verified']),
      excludeReportId != null ? ne(communityReports.id, excludeReportId) : undefined
    ))

  const resolvedReportIds = selectReportsInStaleCells(candidates, staleCells)

  if (resolvedReportIds.length > 0) {
    await dbClient
      .update(communityReports)
      .set({ status: 'resolved', reviewNotes: reason })
      .where(inArray(communityReports.id, resolvedReportIds))
  }

  const invalidated = await dbClient
    .update(worldObservations)
    .set({ isCurrent: false })
    .where(and(
      eq(worldObservations.isCurrent, true),
      eq(worldObservations.state, 'dirty'),
      inArray(worldObservations.locationCellId, staleCells)
    ))
    .returning({ id: worldObservations.id })

  return { resolvedReportIds, invalidatedObservationCount: invalidated.length }
}

/**
 * Placeholder until Task 9 adds blockExclusionZones — returns false (no
 * exclusion zones exist yet). Task 9 replaces this implementation in place.
 */
async function isWithinExclusionZone(blockId: number, lat: number, lng: number): Promise<boolean> {
  const zones = await db
    .select()
    .from(blockExclusionZones)
    .where(eq(blockExclusionZones.blockId, blockId))
  for (const zone of zones) {
    const ring = (zone.polygon as any)?.coordinates?.[0] as [number, number][] | undefined
    if (ring && pointInPolygon(lat, lng, ring.map(([lng2, lat2]) => [lat2, lng2] as [number, number]))) {
      return true
    }
  }
  return false
}
