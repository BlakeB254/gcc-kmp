import { routes } from '@/db/schema'

/**
 * Shared admin-route JSON serializer for /api/admin/routes* handlers.
 * (Next.js route files may only export HTTP verbs, so this lives in lib.)
 */
export function toRouteResponse(route: typeof routes.$inferSelect) {
  return {
    id: route.id,
    code: route.code,
    displayName: route.displayName,
    serviceAreaId: route.serviceAreaId,
    geometry: route.geometry,
    status: route.status,
    claimStatus: route.claimStatus ?? 'closed',
    publishAt: route.publishAt ? new Date(route.publishAt).toISOString() : null,
    unpublishAt: route.unpublishAt ? new Date(route.unpublishAt).toISOString() : null,
    distanceMeters: route.lengthMeters ? parseFloat(route.lengthMeters) : null,
    estimatedDurationMinutes: route.estimatedMinutes
      ? Math.round(parseFloat(route.estimatedMinutes))
      : null,
    payoutAmount: Math.round(parseFloat(route.payoutAmount || '10000')),
    imageUrl: route.imageUrl || null,
    imageLocationLat: route.imageLocationLat ?? null,
    imageLocationLng: route.imageLocationLng ?? null,
    description: route.description || null,
    createdAt: new Date(route.createdAt).toISOString(),
    updatedAt: new Date(route.updatedAt).toISOString(),
  }
}
