/**
 * Geographic lookup for spot reports
 *
 * Uses OpenStreetMap Nominatim (free, no API key) for reverse geocoding.
 * For Chicago coordinates, enriches with community area via zip code mapping.
 */

import { getChicagoCommunityArea } from './chicago-community-areas'

// Chicago bounding box (generous to catch edge cases)
const CHICAGO_BOUNDS = {
  south: 41.64,
  north: 42.02,
  west: -87.94,
  east: -87.52,
}

interface GeoResult {
  city: string | null
  state: string | null
  communityArea: string | null // Chicago-specific
}

function isInChicagoBounds(lat: number, lng: number): boolean {
  return (
    lat >= CHICAGO_BOUNDS.south &&
    lat <= CHICAGO_BOUNDS.north &&
    lng >= CHICAGO_BOUNDS.west &&
    lng <= CHICAGO_BOUNDS.east
  )
}

/**
 * Reverse geocode coordinates to city/state/community area.
 * Non-blocking — returns nulls on failure so spot creation isn't blocked.
 */
export async function reverseGeocode(lat: number, lng: number): Promise<GeoResult> {
  const result: GeoResult = { city: null, state: null, communityArea: null }

  try {
    const url = `https://nominatim.openstreetmap.org/reverse?lat=${lat}&lon=${lng}&format=json&addressdetails=1&zoom=16`
    const response = await fetch(url, {
      headers: { 'User-Agent': 'GCC-SpotReporter/1.0 (globalcommunitycleaners.org)' },
      signal: AbortSignal.timeout(5000),
    })

    if (!response.ok) return result

    const data = await response.json()
    const addr = data.address

    if (!addr) return result

    // Extract city — Nominatim uses various fields depending on location
    result.city = addr.city || addr.town || addr.village || addr.municipality || null
    result.state = addr.state || null

    // For Chicago coordinates, look up community area via zip code
    if (isInChicagoBounds(lat, lng) && addr.postcode) {
      const communityArea = getChicagoCommunityArea(addr.postcode)
      if (communityArea) {
        result.communityArea = communityArea
        result.city = 'Chicago' // Normalize — Nominatim sometimes returns neighborhood names
      }
    }
  } catch {
    // Reverse geocoding is best-effort — don't block spot creation
  }

  return result
}
