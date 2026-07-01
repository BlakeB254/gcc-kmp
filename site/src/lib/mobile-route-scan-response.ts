import { routeScans, scanHotspots, type RouteScanRow, type ScanHotspotRow } from '@/db/schema'
import { db } from '@/db'
import { asc, eq } from 'drizzle-orm'

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

export async function hotspotsForScan(scanId: number): Promise<ScanHotspotRow[]> {
  return db
    .select()
    .from(scanHotspots)
    .where(eq(scanHotspots.scanId, scanId))
    .orderBy(asc(scanHotspots.detectedAt))
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
