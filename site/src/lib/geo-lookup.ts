/**
 * Geographic lookup for spot reports
 *
 * Uses OpenStreetMap Nominatim (free, no API key) for reverse geocoding,
 * plus local polygon-based lookup for Chicago community areas, wards,
 * and police districts.
 */

import { getChicagoCommunityArea } from './chicago-community-areas'
import { findCommunityArea, getPrimaryWard, getPoliceDistrict } from './chicago-geo-data'

// Chicago bounding box (generous to catch edge cases)
const CHICAGO_BOUNDS = {
  south: 41.64,
  north: 42.02,
  west: -87.94,
  east: -87.52,
}

export interface GeoResult {
  city: string | null
  state: string | null
  communityArea: string | null
  communityAreaNumber: number | null
  ward: number | null
  zipCode: string | null
  censusTract: string | null
  policeDistrict: number | null
  streetAddress: string | null
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
 * Look up census tract from Census Bureau geocoder (free, no API key).
 * Returns tract ID or null on failure.
 */
async function lookupCensusTract(lat: number, lng: number): Promise<string | null> {
  try {
    const url = `https://geocoding.geo.census.gov/geocoder/geographies/coordinates?x=${lng}&y=${lat}&benchmark=Public_AR_Current&vintage=Current_Current&format=json`
    const response = await fetch(url, { signal: AbortSignal.timeout(5000) })
    if (!response.ok) return null
    const data = await response.json()
    const geographies = data?.result?.geographies?.['Census Tracts']
    if (geographies && geographies.length > 0) {
      return geographies[0].GEOID || geographies[0].TRACT || null
    }
  } catch {
    // Census API is best-effort
  }
  return null
}

/**
 * Build a street address string from Nominatim address components
 */
function buildStreetAddress(addr: any): string | null {
  const parts: string[] = []
  if (addr.house_number) parts.push(addr.house_number)
  if (addr.road) parts.push(addr.road)
  if (parts.length === 0) return null
  return parts.join(' ')
}

/**
 * Reverse geocode coordinates to full geographic context.
 * Non-blocking — returns nulls on failure so spot creation isn't blocked.
 */
export async function reverseGeocode(lat: number, lng: number): Promise<GeoResult> {
  const result: GeoResult = {
    city: null,
    state: null,
    communityArea: null,
    communityAreaNumber: null,
    ward: null,
    zipCode: null,
    censusTract: null,
    policeDistrict: null,
    streetAddress: null,
  }

  try {
    // Local polygon lookup for Chicago (instant, no API call)
    if (isInChicagoBounds(lat, lng)) {
      const area = findCommunityArea(lat, lng)
      if (area) {
        result.communityArea = area.displayName
        result.communityAreaNumber = area.number
        result.ward = getPrimaryWard(area.number)
        result.policeDistrict = getPoliceDistrict(area.number)
        result.city = 'Chicago'
        result.state = 'Illinois'
      }
    }

    // Nominatim reverse geocode for address, zip, city/state
    const url = `https://nominatim.openstreetmap.org/reverse?lat=${lat}&lon=${lng}&format=json&addressdetails=1&zoom=18`
    const response = await fetch(url, {
      headers: { 'User-Agent': 'GCC-SpotReporter/1.0 (globalcommunitycleaners.org)' },
      signal: AbortSignal.timeout(5000),
    })

    if (response.ok) {
      const data = await response.json()
      const addr = data.address

      if (addr) {
        // City/state from Nominatim (if not already set by polygon lookup)
        if (!result.city) {
          result.city = addr.city || addr.town || addr.village || addr.municipality || null
        }
        if (!result.state) {
          result.state = addr.state || null
        }

        // Zip code
        result.zipCode = addr.postcode || null

        // Street address
        result.streetAddress = buildStreetAddress(addr)

        // Fallback: if polygon lookup missed but zip code matches Chicago
        if (!result.communityArea && result.zipCode) {
          const areaFromZip = getChicagoCommunityArea(result.zipCode)
          if (areaFromZip) {
            result.communityArea = areaFromZip
            result.city = 'Chicago'
          }
        }
      }
    }

    // Census tract lookup (parallel-safe, separate API)
    if (isInChicagoBounds(lat, lng)) {
      result.censusTract = await lookupCensusTract(lat, lng)
    }
  } catch {
    // Reverse geocoding is best-effort — don't block spot creation
  }

  return result
}
