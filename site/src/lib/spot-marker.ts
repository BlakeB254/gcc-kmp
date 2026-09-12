/**
 * One map marker for GET /api/spots.
 *
 * Extracted from the route handler because the inline version omitted media
 * entirely: markers carried no `photoUrl` / `mediaUrls`, so the map popup in
 * NeighborhoodMapSection — which guards on
 * `selectedSpot.photoUrl || selectedSpot.mediaUrls?.length` — rendered no
 * photos at all. The URLs were in the same HTTP response the whole time, in
 * the sibling `data` array built by `toCommunityReport`.
 *
 * Media naming matches `toCommunityReport` on purpose: `mediaUrls` is the full
 * list and `photoUrl` is the first, so both client spellings resolve. The
 * column is `mediaIds`, but it stores URLs.
 *
 * Standalone module with no `@/db` import so the `tsx --test` suite stays
 * loadable without a DATABASE_URL, same as `spot-photo-path.ts`.
 */

export type SpotMarkerSource = {
  id: number | string
  coordinates: unknown
  affectedRadiusMeters?: string | null
  title?: string | null
  type?: string | null
  affectedArea?: unknown
  nextVerificationDue?: string | Date | null
  mediaIds?: unknown
}

export type SpotMarker = {
  id: string
  coordinates: [number, number]
  radiusMeters: number
  label: string
  tags: string[]
  affectedArea: unknown
  needsVerification: boolean
  photoUrl: string | null
  mediaUrls: string[]
}

/** Normalise either storage shape — `[lng, lat]` or `{lat, lng}` — to `[lng, lat]`. */
function toLngLat(raw: unknown): [number, number] {
  if (Array.isArray(raw)) return [raw[0], raw[1]]
  if (raw && typeof raw === 'object') {
    const o = raw as Record<string, number | undefined>
    return [o.lng ?? o.lon ?? 0, o.lat ?? 0]
  }
  return [0, 0]
}

export function toSpotMarker(report: SpotMarkerSource, tags: string[]): SpotMarker {
  const mediaUrls = Array.isArray(report.mediaIds) ? (report.mediaIds as string[]) : []

  return {
    id: String(report.id),
    coordinates: toLngLat(report.coordinates),
    radiusMeters: parseFloat(report.affectedRadiusMeters || '15'),
    label: report.title || (report.type === 'sighting' ? 'Sighting' : 'Volunteer Cleanup'),
    tags,
    affectedArea: (report.affectedArea as unknown) || null,
    needsVerification: report.nextVerificationDue
      ? new Date(report.nextVerificationDue) <= new Date()
      : true,
    // Always present, never undefined — `mediaUrls?.length` on the client is
    // then a real check rather than an unconditional falsy.
    photoUrl: mediaUrls[0] || null,
    mediaUrls,
  }
}
