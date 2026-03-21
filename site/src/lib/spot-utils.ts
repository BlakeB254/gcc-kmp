/**
 * Shared spot report utilities — used by both /api/spots and /api/admin/spots
 */

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
