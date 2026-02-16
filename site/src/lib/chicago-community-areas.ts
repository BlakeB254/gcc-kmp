/**
 * Chicago Community Areas
 *
 * Official 77 community areas from the Chicago Data Portal.
 * For Chicago addresses, we use community area names instead of generic "Chicago"
 * to provide more meaningful local rankings.
 *
 * Source: https://data.cityofchicago.org/Facilities-Geographic-Boundaries/Boundaries-Community-Areas-current-/cauq-8yn6
 */

// Primary zip code to community area mapping
// Note: Some zip codes span multiple community areas - we use the primary/majority area
const ZIP_TO_COMMUNITY_AREA: Record<string, string> = {
  // North Lawndale (GCC Launch Area)
  '60623': 'North Lawndale',
  '60624': 'North Lawndale',

  // West Side
  '60612': 'Near West Side',
  '60607': 'Near West Side',
  '60608': 'Lower West Side',
  '60616': 'Armour Square',
  '60609': 'New City',

  // Austin & Surrounding
  '60644': 'Austin',
  '60651': 'Humboldt Park',
  '60639': 'Belmont Cragin',
  '60641': 'Portage Park',
  '60634': 'Dunning',

  // Note: 60612 spans Near West Side and East Garfield Park - using Near West Side above

  // Humboldt Park / Logan Square / Wicker Park
  '60622': 'West Town',
  '60647': 'Logan Square',
  '60618': 'North Center',
  '60625': 'Lincoln Square',

  // South Side
  '60621': 'Englewood',
  '60636': 'West Englewood',
  '60620': 'Auburn Gresham',
  '60619': 'Chatham',
  '60637': 'Greater Grand Crossing',
  '60649': 'South Shore',
  '60617': 'South Chicago',
  '60633': 'Hegewisch',
  '60628': 'Roseland',
  '60643': 'Morgan Park',
  '60655': 'Mount Greenwood',
  '60652': 'Clearing',
  '60629': 'Chicago Lawn',
  '60632': 'Brighton Park',
  '60638': 'Garfield Ridge',
  '60804': 'Cicero', // Adjacent suburb, not Chicago

  // North Side
  '60614': 'Lincoln Park',
  '60657': 'Lake View',
  '60613': 'Lake View',
  '60640': 'Uptown',
  '60660': 'Edgewater',
  '60626': 'Rogers Park',
  '60645': 'West Ridge',
  '60659': 'West Ridge',
  '60646': 'Forest Glen',
  '60631': 'Edison Park',
  '60656': "O'Hare",
  '60630': 'Jefferson Park',
  '60642': 'Near North Side',
  '60654': 'River North',
  '60610': 'Near North Side',
  '60611': 'Near North Side',

  // Downtown / Loop
  '60601': 'Loop',
  '60602': 'Loop',
  '60603': 'Loop',
  '60604': 'Loop',
  '60605': 'Near South Side',
  '60606': 'Loop',
  '60661': 'Loop',

  // Far South Side
  '60827': 'Riverdale',
  // Note: 60633 (Hegewisch) and 60617 (South Chicago) already listed above
  '60615': 'Hyde Park',
  '60653': 'Kenwood',
}

// All 77 official Chicago community areas
export const CHICAGO_COMMUNITY_AREAS = [
  'Albany Park',
  'Archer Heights',
  'Armour Square',
  'Ashburn',
  'Auburn Gresham',
  'Austin',
  'Avalon Park',
  'Avondale',
  'Belmont Cragin',
  'Beverly',
  'Bridgeport',
  'Brighton Park',
  'Burnside',
  'Calumet Heights',
  'Chatham',
  'Chicago Lawn',
  'Clearing',
  'Douglas',
  'Dunning',
  'East Garfield Park',
  'East Side',
  'Edgewater',
  'Edison Park',
  'Englewood',
  'Forest Glen',
  'Fuller Park',
  'Gage Park',
  'Garfield Ridge',
  'Grand Boulevard',
  'Greater Grand Crossing',
  'Hegewisch',
  'Hermosa',
  'Humboldt Park',
  'Hyde Park',
  'Irving Park',
  'Jefferson Park',
  'Kenwood',
  'Lake View',
  'Lincoln Park',
  'Lincoln Square',
  'Logan Square',
  'Loop',
  'Lower West Side',
  'McKinley Park',
  'Montclare',
  'Morgan Park',
  'Mount Greenwood',
  'Near North Side',
  'Near South Side',
  'Near West Side',
  'New City',
  'North Center',
  'North Lawndale',
  'North Park',
  'Norwood Park',
  "O'Hare",
  'Oakland',
  'Portage Park',
  'Pullman',
  'Riverdale',
  'Rogers Park',
  'Roseland',
  'South Chicago',
  'South Deering',
  'South Lawndale',
  'South Shore',
  'Uptown',
  'Washington Heights',
  'Washington Park',
  'West Elsdon',
  'West Englewood',
  'West Garfield Park',
  'West Lawn',
  'West Pullman',
  'West Ridge',
  'West Town',
  'Woodlawn',
]

/**
 * Detect if an address is in Chicago based on city name or zip code
 */
export function isChicagoAddress(city: string, stateCode: string, zip?: string): boolean {
  // Direct city match
  if (city.toLowerCase() === 'chicago' && stateCode.toUpperCase() === 'IL') {
    return true
  }

  // Check if zip code is a known Chicago zip
  if (zip && ZIP_TO_COMMUNITY_AREA[zip]) {
    return true
  }

  return false
}

/**
 * Get Chicago community area from zip code
 * Returns null if not a Chicago zip code
 */
export function getChicagoCommunityArea(zip: string): string | null {
  return ZIP_TO_COMMUNITY_AREA[zip] || null
}

/**
 * Get the neighborhood name for display
 * For Chicago: returns community area name
 * For other cities: returns the city name
 */
export function getNeighborhoodForDisplay(
  city: string,
  stateCode: string,
  zip?: string
): { neighborhood: string; isChicago: boolean } {
  if (isChicagoAddress(city, stateCode, zip)) {
    const communityArea = zip ? getChicagoCommunityArea(zip) : null
    return {
      neighborhood: communityArea || 'Chicago',
      isChicago: true,
    }
  }

  return {
    neighborhood: city,
    isChicago: false,
  }
}

/**
 * Get the location key for ranking calculations
 * This determines how users are grouped for local rankings
 *
 * For Chicago: Groups by community area (e.g., "North Lawndale, IL")
 * For other US cities: Groups by city + state (e.g., "Los Angeles, CA")
 * For international: Groups by country
 */
export function getLocationKey(
  city: string,
  stateCode: string,
  countryCode: string,
  zip?: string
): {
  neighborhoodKey: string | null  // Community area or null
  cityKey: string                  // City, State or Country
  stateKey: string                 // State/Province
  countryKey: string               // Country code
} {
  // For Chicago, use community area as neighborhood
  if (isChicagoAddress(city, stateCode, zip)) {
    const communityArea = zip ? getChicagoCommunityArea(zip) : null
    return {
      neighborhoodKey: communityArea ? `${communityArea}, IL` : null,
      cityKey: 'Chicago, IL',
      stateKey: 'IL',
      countryKey: countryCode,
    }
  }

  // For other US addresses
  if (countryCode === 'US') {
    return {
      neighborhoodKey: null,
      cityKey: `${city}, ${stateCode}`,
      stateKey: stateCode,
      countryKey: countryCode,
    }
  }

  // For international addresses
  return {
    neighborhoodKey: null,
    cityKey: city,
    stateKey: stateCode,
    countryKey: countryCode,
  }
}

/**
 * North Lawndale launch area zip codes
 */
export const LAUNCH_AREA_ZIPS = ['60623', '60624']

/**
 * Check if zip code is in the GCC launch area (North Lawndale)
 */
export function isInLaunchArea(zip: string): boolean {
  return LAUNCH_AREA_ZIPS.includes(zip)
}

/**
 * Get display name for a location based on granularity
 */
export function getLocationDisplayName(
  city: string,
  stateCode: string,
  countryCode: string,
  zip?: string
): string {
  const { neighborhood, isChicago } = getNeighborhoodForDisplay(city, stateCode, zip)

  if (isChicago && neighborhood !== 'Chicago') {
    return `${neighborhood}, Chicago`
  }

  if (countryCode === 'US') {
    return `${city}, ${stateCode}`
  }

  return city
}
