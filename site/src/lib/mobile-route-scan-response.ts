import { routeScans, scanHotspots, worldObservations, type RouteScanRow, type ScanHotspotRow } from '@/db/schema'
import { db } from '@/db'
import { and, asc, eq, inArray } from 'drizzle-orm'

const validTags = new Set([
  'trash',
  'leaves',
  'glass',
  'metal',
  'tires',
  'furniture',
  'construction',
  'organic',
  'hazardous',
  'electronics',
  'clothing',
  'graffiti',
  'other',
])

export function normalizeScanPhase(phase: unknown): string {
  const value = String(phase ?? 'scouting').toLowerCase()
  if (value === 'scout_review') return value
  if (value === 'cleaning' || value === 'submitting' || value === 'complete') return value
  return 'scouting'
}

/** The 2-lap flow in phase form: lap 1 = scouting, lap 2 = cleaning. */
const SCAN_PHASE_ORDER = ['scouting', 'scout_review', 'cleaning', 'submitting', 'complete']

/**
 * A scan phase may only stay put (idempotent client retries) or advance one
 * step. The 2-lap invariant used to live entirely in client navigation — any
 * PATCH could jump `scouting → complete` and skip the cleanup lap; now the
 * server refuses out-of-order writes. `submitting → complete` normally
 * happens via the scan-complete endpoint, but the step is legal here too.
 */
export function isValidScanPhaseTransition(from: unknown, to: unknown): boolean {
  const fromIndex = SCAN_PHASE_ORDER.indexOf(normalizeScanPhase(from))
  const toIndex = SCAN_PHASE_ORDER.indexOf(normalizeScanPhase(to))
  return toIndex === fromIndex || toIndex === fromIndex + 1
}

/**
 * Validates the proof guided-route clients emit at the scouting → review
 * boundary. `null` means the client sent neither proof field; the phase
 * endpoint now refuses that like a failing proof (it used to let it through
 * for older TestFlight builds — finding H2).
 */
export function validateLapOneProof(
  body: Record<string, unknown> | null,
  requiresCloseCoverage: boolean,
): boolean | null {
  if (!body || (body.closeCoveragePercent === undefined && body.loopClosed === undefined)) {
    return null
  }
  const coverage = Number(body.coveragePercent)
  const closeCoverage = Number(body.closeCoveragePercent)
  return Number.isFinite(coverage) &&
    coverage >= 95 &&
    body.loopClosed === true &&
    (!requiresCloseCoverage || (Number.isFinite(closeCoverage) && closeCoverage >= 80))
}

export function normalizeDeviceType(deviceType: unknown): string {
  return String(deviceType ?? '').toLowerCase() === 'ar' ? 'ar' : 'gps'
}

export function normalizeHotspotStatus(status: unknown): string {
  const value = String(status ?? 'detected').toLowerCase()
  if (value === 'cleaned' || value === 'skipped') return value
  return 'detected'
}

export function normalizeTags(tags: unknown): string[] {
  const rawTags = Array.isArray(tags) ? tags : []
  return rawTags
    .map((tag) => String(tag).toLowerCase())
    .filter((tag) => validTags.has(tag))
}

function parseStoredTags(value: string | null): string[] {
  if (!value) return []
  try {
    return normalizeTags(JSON.parse(value))
  } catch {
    return []
  }
}

/**
 * Hotspots for a scan. During the CLEANING-phase (and the phases that
 * follow it — 'cleaning', 'submitting', 'complete'), any hotspot flagged
 * private property (via a `worldObservations` row with
 * `excludedPrivateProperty = true`) is filtered out, so excluded hotspots
 * never surface as lap-2 clean instructions. During 'scouting' and
 * 'scout_review' (or any other/unrecognized phase), hotspots are returned
 * unfiltered — a worker's in-progress scouting view should never silently
 * drop something they just scouted.
 */
export async function hotspotsForScan(scanId: number, phase: string): Promise<ScanHotspotRow[]> {
  const hotspots = await db
    .select()
    .from(scanHotspots)
    .where(eq(scanHotspots.scanId, scanId))
    .orderBy(asc(scanHotspots.detectedAt))

  if (hotspots.length === 0) return hotspots

  const shouldFilterExcluded = phase === 'cleaning' || phase === 'submitting' || phase === 'complete'
  if (!shouldFilterExcluded) return hotspots

  const hotspotIds = hotspots.map((h) => h.id)
  const excludedRows = await db
    .select({ scanHotspotId: worldObservations.scanHotspotId })
    .from(worldObservations)
    .where(and(
      inArray(worldObservations.scanHotspotId, hotspotIds),
      eq(worldObservations.excludedPrivateProperty, true)
    ))

  if (excludedRows.length === 0) return hotspots

  const excludedIds = new Set(excludedRows.map((r) => r.scanHotspotId))
  return hotspots.filter((h) => !excludedIds.has(h.id))
}

export async function findRouteScan(scanId: number): Promise<RouteScanRow | null> {
  const [scan] = await db
    .select()
    .from(routeScans)
    .where(eq(routeScans.id, scanId))
    .limit(1)
  return scan ?? null
}

export function canAccessRouteScan(row: RouteScanRow, auth: { workerId: number; role: string }): boolean {
  return auth.role === 'admin' || row.workerId === auth.workerId
}

export function toMobileScanHotspot(row: ScanHotspotRow) {
  return {
    id: row.id,
    scanId: row.scanId,
    lat: row.lat,
    lng: row.lng,
    trashTypes: parseStoredTags(row.trashTypes),
    severity: row.severity,
    estimatedVolumeLiters: row.estimatedVolumeLiters,
    estimatedWeightLbs: row.estimatedWeightLbs,
    confidence: row.confidence,
    beforePhotoUrl: row.beforePhotoUrl,
    afterPhotoUrl: row.afterPhotoUrl,
    arAnchorData: row.arAnchorData,
    depthData: row.depthData,
    detectedAt: row.detectedAt,
    cleanedAt: row.cleanedAt,
    verificationScore: row.verificationScore,
    status: normalizeHotspotStatus(row.status),
  }
}

export function toMobileRouteScan(row: RouteScanRow, hotspots: ScanHotspotRow[] = []) {
  return {
    id: row.id,
    routeId: row.routeId,
    workerId: row.workerId,
    deviceType: normalizeDeviceType(row.deviceType),
    phase: normalizeScanPhase(row.phase),
    coveragePercent: row.coveragePercent,
    scoutStartedAt: row.scoutStartedAt,
    scoutCompletedAt: row.scoutCompletedAt,
    cleanStartedAt: row.cleanStartedAt,
    cleanCompletedAt: row.cleanCompletedAt,
    startLat: row.startLat,
    startLng: row.startLng,
    scoutPathGeoJson: row.scoutPathGeoJson,
    cleanPathGeoJson: row.cleanPathGeoJson,
    totalTrashVolumeLiters: row.totalTrashVolumeLiters,
    totalTrashWeightLbs: row.totalTrashWeightLbs,
    pointsAwarded: row.pointsAwarded,
    status: row.status,
    performedByAdminId: row.performedByAdminId ?? null,
    hotspots: hotspots.map(toMobileScanHotspot),
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
  }
}
