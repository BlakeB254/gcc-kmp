import type { BountyRow, CommunityReport, WorldObservationRow } from '@/db/schema'
import { servableMediaUrl } from '@/lib/observation-media'

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

/**
 * Amendment 2 / Task 1b, Change 3: seed a bounty from a `worldObservations`
 * row instead of a `communityReports` row (bounty continuity — scan
 * submissions no longer produce reports, but a bounty still needs
 * somewhere to seed from). Caller is responsible for validating the
 * observation is eligible (isCurrent/state/excludedPrivateProperty) before
 * calling this — this function only derives field values.
 *
 * `radiusMeters: 15` (not the report path's 200 default): an observation is
 * a single ~10m grid cell, a much tighter location signal than a report's
 * affectedRadiusMeters — a wide 200m radius would overstate the bounty's
 * actual coverage. `observerId`/`city`/`communityArea`/`neighborhood` have
 * no source column on worldObservations, so they're left null/defaulted;
 * the caller's insert defaults `city` the same way the schema column does.
 */
export function observationToBountySource(observation: WorldObservationRow) {
  const label = observation.itemType ? titleCase(observation.itemType) : 'Litter'

  return {
    title: `Cleanup opportunity: ${label}`,
    description: observation.itemType ? `Scan-detected ${label.toLowerCase()}` : null,
    locationLat: observation.lat,
    locationLng: observation.lng,
    radiusMeters: 15,
    city: 'Chicago',
    communityArea: null as string | null,
    neighborhood: null as string | null,
    mediaUrls: (() => {
      // Flag-down aware (see servableMediaUrl): flagged photos never reach
      // bounty cards.
      const url = servableMediaUrl(observation)
      return url ? [url] : []
    })(),
    aiAnalysis: null as { estimatedWeightLbs: number | null; trashTypes: string[]; confidence: number | null; severity: string | null } | null,
  }
}

export type BountyTriggerValidation =
  | { ok: true; triggerReportId: number | null; triggerObservationId: number | null }
  | { ok: false; message: string }

/**
 * Amendment 2 / Task 1b, Change 3: validate the "exactly one of
 * triggerReportId/triggerObservationId, and it must be a positive integer"
 * rule for POST /api/bounties. Pulled out as a pure function (no DB access)
 * so the rule is directly unit-testable.
 */
export function resolveBountyTrigger(body: { triggerReportId?: unknown; triggerObservationId?: unknown }): BountyTriggerValidation {
  const hasReportId = body.triggerReportId !== undefined && body.triggerReportId !== null
  const hasObservationId = body.triggerObservationId !== undefined && body.triggerObservationId !== null

  if (hasReportId === hasObservationId) {
    // Both present, or neither present.
    return { ok: false, message: 'Provide exactly one of triggerReportId or triggerObservationId' }
  }

  if (hasReportId) {
    const triggerReportId = Number(body.triggerReportId)
    if (!Number.isInteger(triggerReportId) || triggerReportId <= 0) {
      return { ok: false, message: 'Choose an active spot report before posting a bounty' }
    }
    return { ok: true, triggerReportId, triggerObservationId: null }
  }

  const triggerObservationId = Number(body.triggerObservationId)
  if (!Number.isInteger(triggerObservationId) || triggerObservationId <= 0) {
    return { ok: false, message: 'Choose a valid observation before posting a bounty' }
  }
  return { ok: true, triggerReportId: null, triggerObservationId }
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
    // Additive field (Amendment 2 / Task 1b, Change 3) — wire-safe for old
    // clients: the shared Kotlin `Bounty` model deserializes via `GccJson`
    // (ignoreUnknownKeys = true, see shared/.../serialization/GccJson.kt),
    // so a client that predates this field simply ignores it.
    triggerObservationId: row.triggerObservationId,
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
