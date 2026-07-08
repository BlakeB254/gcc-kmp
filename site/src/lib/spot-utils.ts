/**
 * Shared spot report utilities — used by both /api/spots and /api/admin/spots
 */
import type { NewCommunityReport } from '@/db/schema'

// Tag keywords for auto-tagging spots based on description
const TAG_KEYWORDS: Record<string, string[]> = {
  trash: ['trash', 'garbage', 'rubbish', 'waste', 'litter', 'dump'],
  leaves: ['leaves', 'leaf', 'yard waste', 'branches', 'twigs'],
  glass: ['glass', 'bottle', 'broken glass', 'shattered'],
  metal: ['metal', 'can', 'cans', 'aluminum', 'steel', 'scrap'],
  tires: ['tire', 'tires', 'rubber'],
  furniture: ['furniture', 'couch', 'sofa', 'chair', 'mattress', 'bed', 'table', 'dresser'],
  construction: ['construction', 'concrete', 'brick', 'drywall', 'lumber', 'debris', 'demolition'],
  organic: ['organic', 'food', 'compost', 'rot'],
  hazardous: ['hazardous', 'chemical', 'paint', 'oil', 'asbestos', 'toxic'],
  electronics: ['electronics', 'tv', 'computer', 'monitor', 'appliance', 'fridge', 'refrigerator'],
  clothing: ['clothing', 'clothes', 'fabric', 'shoes', 'textile'],
}

export function autoTagFromDescription(description: string | null): string[] {
  if (!description) return []
  const lower = description.toLowerCase()
  const tags: string[] = []
  for (const [tag, keywords] of Object.entries(TAG_KEYWORDS)) {
    if (keywords.some((kw) => lower.includes(kw))) {
      tags.push(tag)
    }
  }
  return tags
}

/** Resolve tags: use DB tags if non-empty, otherwise auto-tag from description */
export function resolveSpotTags(dbTags: unknown, description: string | null): string[] {
  if (Array.isArray(dbTags) && dbTags.length > 0) return dbTags
  return autoTagFromDescription(description)
}

/** Convert DB row to CommunityReport shape the iOS app expects */
export function toCommunityReport(r: any, precomputedTags?: string[]) {
  const coords = r.coordinates as { lat?: number; lng?: number } | [number, number] | null
  return {
    id: r.id,
    reporterId: r.reporterId,
    reporterName: r.reporterName,
    type: r.type,
    coordinates: Array.isArray(coords)
      ? { lng: coords[0], lat: coords[1] }
      : coords ?? { lng: 0, lat: 0 },
    description: r.description,
    mediaUrls: (r.mediaIds as string[]) || [],
    mediaCaptions: (r.mediaCaptions as string[]) || [],
    status: r.status,
    upvotes: r.thankCount || 0,
    verifiedBy: r.reviewedById,
    resolvedAt: r.resolvedByJobId && r.updatedAt ? new Date(r.updatedAt).toISOString() : null,
    bountyId: null,
    observerRewardPoints: 0,
    aiAnalysis: r.onDeviceAiResult || null,
    affectedRadiusMeters: r.affectedRadiusMeters ? parseFloat(r.affectedRadiusMeters) : 15.0,
    affectedArea: r.affectedArea || null,
    tags: precomputedTags || resolveSpotTags(r.tags, r.description),
    photoUrl: ((r.mediaIds as string[]) || [])[0] || null,
    lastVerifiedAt: r.lastVerifiedAt ? new Date(r.lastVerifiedAt).toISOString() : null,
    verificationCount: r.verificationCount || 0,
    nextVerificationDue: r.nextVerificationDue ? new Date(r.nextVerificationDue).toISOString() : null,
    needsVerification: r.nextVerificationDue ? new Date(r.nextVerificationDue) <= new Date() : true,
    depthAnalysis: r.depthAnalysis || null,
    geoCity: r.geoCity || null,
    geoState: r.geoState || null,
    geoCommunityArea: r.geoCommunityArea || null,
    geoCommunityAreaNumber: r.geoCommunityAreaNumber || null,
    geoWard: r.geoWard || null,
    geoZipCode: r.geoZipCode || null,
    geoCensusTract: r.geoCensusTract || null,
    geoPoliceDistrict: r.geoPoliceDistrict || null,
    geoStreetAddress: r.geoStreetAddress || null,
    source: r.source || null, // 'admin' | 'user' | null
    createdAt: new Date(r.createdAt).toISOString(),
    updatedAt: new Date(r.updatedAt).toISOString(),
  }
}

/**
 * Whether a POST /api/spots submission came from the environment-scan
 * camera flow (as opposed to a genuine manual "Report a Spot"). The
 * world-scan flow always carries an `observedItems` array (even empty —
 * presence of the array IS the scan marker, per SpotViewModel), and a
 * live-capture attestation also marks a scan: deployed clients send
 * `observedItems = null` when the scan confirmed zero items, and a
 * zero-item scan must still be recognized as a scan (Amendment 2 / Task
 * 1b — supersede semantics for zero-item scans predate this change; this
 * helper is the same decision, just factored out so it's independently
 * testable and shared with the points/report-insert logic).
 */
export function isScanSubmission(observedItems: unknown, captureAttestation: unknown): boolean {
  return Array.isArray(observedItems) || Boolean(captureAttestation)
}

/**
 * Amendment 2 / Task 1b, Change 2: the points-ledger action + reference
 * type a POST /api/spots submission logs. Scan submissions log
 * `observation_logged` against the anchor world observation (no report row
 * exists to reference); manual submissions keep the pre-existing
 * `spot_created` against the new report row. Same 10 points either way —
 * only the action/referenceType differ.
 */
export function resolvePointsAction(isScan: boolean): { action: 'spot_created' | 'observation_logged'; referenceType: 'spot' | 'world_observation' } {
  return isScan
    ? { action: 'observation_logged', referenceType: 'world_observation' }
    : { action: 'spot_created', referenceType: 'spot' }
}

/**
 * Synthetic-pin id offset shared with the client convention
 * (shared/src/commonMain/kotlin/com/gcc/shared/model/WorldObservation.kt
 * SCAN_OBSERVATION_PIN_ID_BASE / toScanMapPin()). Mirrored here rather than
 * imported — the server has no Kotlin dependency — so keep the two in sync
 * if the constant ever moves.
 */
export const SCAN_OBSERVATION_PIN_ID_BASE = 1_000_000

/**
 * Amendment 2 / Task 1b, Change 1 (wire-compat, CRITICAL): scan submissions
 * no longer insert a `communityReports` row, but deployed clients
 * (`SpotViewModel.submitReport`) dereference `spotResponse.data.report`
 * NON-NULL and pass its id into the env-scan session manifest's
 * `communityReportId`. This builds a SYNTHETIC, non-persisted report from
 * the same `values` object that would have been inserted, so it round-trips
 * through the client's `CommunityReport` deserializer exactly like a real
 * row would.
 *
 * - `id = SCAN_OBSERVATION_PIN_ID_BASE + anchorObservationId` — the same
 *   convention `WorldObservation.toScanMapPin()` uses for scan-sourced map
 *   pins, so this id can never collide with a real community-report id
 *   (serial ids stay far below 1_000_000 for the practical future) and is
 *   recognizable on sight as synthetic.
 * - `status` is inherited from `values.status`, which the caller sets to
 *   'pending' (not e.g. 'resolved') for both the manual and scan paths so
 *   that if any code path ever mistakes this id for a real report (it
 *   matches no row in `communityReports`), the failure mode reads as "an
 *   ordinary unreviewed report" rather than something that looks
 *   authoritatively resolved/verified — fails soft.
 */
export function buildSyntheticScanReport(
  values: NewCommunityReport,
  anchorObservationId: number,
  nowIso: string,
) {
  return toCommunityReport({
    ...values,
    id: SCAN_OBSERVATION_PIN_ID_BASE + anchorObservationId,
    thankCount: 0,
    reviewedById: null,
    resolvedByJobId: null,
    createdAt: nowIso,
    updatedAt: nowIso,
  })
}
