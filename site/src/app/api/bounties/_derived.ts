import type { BountyRow, CommunityReport } from '@/db/schema'

export const BOUNTY_MINIMUM_CENTS = 1000

type NormalizedCoordinates = {
  lat: number
  lng: number
}

type OnDeviceAiResult = {
  trashTypes?: string[]
  estimatedWeightLbs?: number
  confidence?: number
  severity?: number
}

function titleCase(value: string): string {
  return value
    .replace(/_/g, ' ')
    .replace(/\b\w/g, (char) => char.toUpperCase())
}

function normalizeCoordinates(raw: unknown): NormalizedCoordinates | null {
  if (Array.isArray(raw)) {
    const lng = Number(raw[0])
    const lat = Number(raw[1])
    return Number.isFinite(lat) && Number.isFinite(lng) ? { lat, lng } : null
  }

  if (raw && typeof raw === 'object') {
    const obj = raw as { lat?: unknown; lng?: unknown; lon?: unknown }
    const lat = Number(obj.lat)
    const lng = Number(obj.lng ?? obj.lon)
    return Number.isFinite(lat) && Number.isFinite(lng) ? { lat, lng } : null
  }

  return null
}

function stringArray(raw: unknown): string[] {
  return Array.isArray(raw)
    ? raw.filter((item): item is string => typeof item === 'string' && item.length > 0)
    : []
}

export function reportToBountySource(report: CommunityReport) {
  const coordinates = normalizeCoordinates(report.coordinates)
  if (!coordinates) return null

  const tags = stringArray(report.tags)
  const mediaUrls = stringArray(report.mediaIds)
  const ai = report.onDeviceAiResult as OnDeviceAiResult | null
  const tagLabel = tags.length > 0 ? tags.map(titleCase).join(', ') : 'Reported spot'

  return {
    title: report.title || `Cleanup opportunity: ${tagLabel}`,
    description: report.description,
    locationLat: coordinates.lat,
    locationLng: coordinates.lng,
    radiusMeters: Number(report.affectedRadiusMeters ?? 30),
    city: report.geoCity || 'Chicago',
    communityArea: report.geoCommunityArea,
    neighborhood: report.geoStreetAddress || report.locationAddress || report.locationNeighborhood,
    triggerReportId: report.id,
    observerId: report.reporterId,
    firstRefusalExpiresAt: null,
    claimedBy: null,
    jobId: null,
    mediaUrls,
    completionMediaUrls: null,
    aiAnalysis: ai ? {
      estimatedWeightLbs: ai.estimatedWeightLbs ?? null,
      trashTypes: ai.trashTypes ?? [],
      confidence: ai.confidence ?? null,
      severity: ai.severity != null ? `${ai.severity}/5` : null,
    } : null,
    expiresAt: report.mapExpiresAt,
  }
}

/** Postgres timestamp values arrive as "2026-07-03 15:35:28.218012+00" —
 *  the mobile client parses kotlinx Instant, which requires ISO-8601. */
function toIso(value: unknown): string | null {
  if (value == null) return null
  const d = new Date(String(value))
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

export function bountyRowToApiBounty(row: BountyRow) {
  return {
    id: row.id,
    posterType: row.posterType === 'sponsor' ? 'sponsor' : 'worker',
    posterId: row.posterId ?? 0,
    walletId: row.walletId ?? 0,
    title: row.title,
    description: row.description,
    amount: Number(row.amount) || 0,
    status: row.status || 'open',
    locationLat: Number(row.locationLat),
    locationLng: Number(row.locationLng),
    radiusMeters: Number(row.radiusMeters) || 200,
    city: row.city || 'Chicago',
    communityArea: row.communityArea,
    neighborhood: row.neighborhood,
    triggerReportId: row.triggerReportId,
    observerId: row.observerId,
    firstRefusalExpiresAt: toIso(row.firstRefusalExpiresAt),
    claimedBy: row.claimedBy,
    jobId: row.jobId,
    mediaUrls: stringArray(row.mediaUrls),
    completionMediaUrls: stringArray(row.completionMediaUrls),
    aiAnalysis: row.aiAnalysis,
    expiresAt: toIso(row.expiresAt),
    pledgeCount: row.pledgeCount ?? 1,
    spotterTipAmount: row.spotterTipAmount != null ? Number(row.spotterTipAmount) : null,
    spotterTipPaidAt: toIso(row.spotterTipPaidAt),
    createdAt: toIso(row.createdAt),
    updatedAt: toIso(row.updatedAt),
  }
}
