/**
 * Waitlist Ranking Utilities
 *
 * Helper functions for formatting and displaying rankings.
 */

export type EarlyAdopterTier = 'founder' | 'pioneer' | 'early' | 'standard'

export interface Rankings {
  global: number
  country: number
  state: number
  city: number
  neighborhood?: number
}

export interface TierInfo {
  tier: EarlyAdopterTier
  label: string
  emoji: string
  color: string
  bgColor: string
  borderColor: string
  description: string
}

/**
 * Get tier information based on tier value
 */
export function getTierInfo(tier: EarlyAdopterTier): TierInfo {
  const tiers: Record<EarlyAdopterTier, TierInfo> = {
    founder: {
      tier: 'founder',
      label: 'Founder',
      emoji: '👑',
      color: 'text-amber-500',
      bgColor: 'bg-gradient-to-br from-amber-400 to-yellow-600',
      borderColor: 'border-amber-400',
      description: 'One of the first 100 supporters worldwide',
    },
    pioneer: {
      tier: 'pioneer',
      label: 'Pioneer',
      emoji: '🚀',
      color: 'text-slate-400',
      bgColor: 'bg-gradient-to-br from-slate-300 to-slate-500',
      borderColor: 'border-slate-400',
      description: 'One of the first 1,000 supporters worldwide',
    },
    early: {
      tier: 'early',
      label: 'Early Adopter',
      emoji: '⭐',
      color: 'text-orange-500',
      bgColor: 'bg-gradient-to-br from-orange-400 to-amber-700',
      borderColor: 'border-orange-500',
      description: 'One of the first 10,000 supporters worldwide',
    },
    standard: {
      tier: 'standard',
      label: 'Supporter',
      emoji: '🌱',
      color: 'text-green-500',
      bgColor: 'bg-gradient-to-br from-green-400 to-green-600',
      borderColor: 'border-green-500',
      description: 'Helping grow the movement',
    },
  }

  return tiers[tier] || tiers.standard
}

/**
 * Format a rank number with # prefix and comma separators
 */
export function formatRank(rank: number): string {
  return `#${rank.toLocaleString()}`
}

/**
 * Get ordinal suffix for a number (1st, 2nd, 3rd, etc.)
 */
export function getOrdinalSuffix(n: number): string {
  const s = ['th', 'st', 'nd', 'rd']
  const v = n % 100
  return n + (s[(v - 20) % 10] || s[v] || s[0])
}

/**
 * Get country flag emoji from country code
 */
export function getCountryFlag(countryCode: string): string {
  // Convert country code to regional indicator symbols
  const codePoints = countryCode
    .toUpperCase()
    .split('')
    .map((char) => 127397 + char.charCodeAt(0))
  return String.fromCodePoint(...codePoints)
}

/**
 * Get state emoji (US-specific for now)
 */
export function getStateEmoji(): string {
  return '📍'
}

/**
 * Get city emoji
 */
export function getCityEmoji(): string {
  return '🏙️'
}

/**
 * Get neighborhood emoji
 */
export function getNeighborhoodEmoji(): string {
  return '🏘️'
}

/**
 * Format location for display
 */
export function formatLocation(location: {
  city: string
  stateCode: string
  countryCode: string
  neighborhood?: string
}): string {
  const parts = [location.city, location.stateCode]
  if (location.countryCode !== 'US') {
    parts.push(location.countryCode)
  }
  return parts.join(', ')
}

/**
 * US State codes and names for dropdown
 */
export const US_STATES = [
  { code: 'AL', name: 'Alabama' },
  { code: 'AK', name: 'Alaska' },
  { code: 'AZ', name: 'Arizona' },
  { code: 'AR', name: 'Arkansas' },
  { code: 'CA', name: 'California' },
  { code: 'CO', name: 'Colorado' },
  { code: 'CT', name: 'Connecticut' },
  { code: 'DE', name: 'Delaware' },
  { code: 'FL', name: 'Florida' },
  { code: 'GA', name: 'Georgia' },
  { code: 'HI', name: 'Hawaii' },
  { code: 'ID', name: 'Idaho' },
  { code: 'IL', name: 'Illinois' },
  { code: 'IN', name: 'Indiana' },
  { code: 'IA', name: 'Iowa' },
  { code: 'KS', name: 'Kansas' },
  { code: 'KY', name: 'Kentucky' },
  { code: 'LA', name: 'Louisiana' },
  { code: 'ME', name: 'Maine' },
  { code: 'MD', name: 'Maryland' },
  { code: 'MA', name: 'Massachusetts' },
  { code: 'MI', name: 'Michigan' },
  { code: 'MN', name: 'Minnesota' },
  { code: 'MS', name: 'Mississippi' },
  { code: 'MO', name: 'Missouri' },
  { code: 'MT', name: 'Montana' },
  { code: 'NE', name: 'Nebraska' },
  { code: 'NV', name: 'Nevada' },
  { code: 'NH', name: 'New Hampshire' },
  { code: 'NJ', name: 'New Jersey' },
  { code: 'NM', name: 'New Mexico' },
  { code: 'NY', name: 'New York' },
  { code: 'NC', name: 'North Carolina' },
  { code: 'ND', name: 'North Dakota' },
  { code: 'OH', name: 'Ohio' },
  { code: 'OK', name: 'Oklahoma' },
  { code: 'OR', name: 'Oregon' },
  { code: 'PA', name: 'Pennsylvania' },
  { code: 'RI', name: 'Rhode Island' },
  { code: 'SC', name: 'South Carolina' },
  { code: 'SD', name: 'South Dakota' },
  { code: 'TN', name: 'Tennessee' },
  { code: 'TX', name: 'Texas' },
  { code: 'UT', name: 'Utah' },
  { code: 'VT', name: 'Vermont' },
  { code: 'VA', name: 'Virginia' },
  { code: 'WA', name: 'Washington' },
  { code: 'WV', name: 'West Virginia' },
  { code: 'WI', name: 'Wisconsin' },
  { code: 'WY', name: 'Wyoming' },
  { code: 'DC', name: 'Washington, D.C.' },
]

/**
 * Get state name from code
 */
export function getStateName(stateCode: string): string {
  const state = US_STATES.find((s) => s.code === stateCode)
  return state?.name || stateCode
}

/**
 * Get state code from US zip code (first 3 digits determine region)
 * Returns null if zip is invalid or not recognized
 */
export function getStateFromZip(zip: string): string | null {
  if (!zip || zip.length < 3) return null

  const prefix = parseInt(zip.substring(0, 3), 10)
  if (isNaN(prefix)) return null

  // Zip code prefix to state mapping
  // Based on USPS zip code ranges
  if (prefix >= 995 && prefix <= 999) return 'AK'
  if (prefix >= 967 && prefix <= 968) return 'HI'
  if (prefix >= 900 && prefix <= 961) return 'CA'
  if (prefix >= 970 && prefix <= 979) return 'OR'
  if (prefix >= 980 && prefix <= 994) return 'WA'
  if (prefix >= 889 && prefix <= 898) return 'NV'
  if (prefix >= 832 && prefix <= 838) return 'ID'
  if (prefix >= 820 && prefix <= 831) return 'WY'
  if (prefix >= 840 && prefix <= 847) return 'UT'
  if (prefix >= 850 && prefix <= 865) return 'AZ'
  if (prefix >= 870 && prefix <= 884) return 'NM'
  if (prefix >= 800 && prefix <= 816) return 'CO'
  if (prefix >= 590 && prefix <= 599) return 'MT'
  if (prefix >= 570 && prefix <= 577) return 'SD'
  if (prefix >= 580 && prefix <= 588) return 'ND'
  if (prefix >= 680 && prefix <= 693) return 'NE'
  if (prefix >= 660 && prefix <= 679) return 'KS'
  if (prefix >= 730 && prefix <= 749) return 'OK'
  if (prefix >= 750 && prefix <= 799) return 'TX'
  if (prefix >= 700 && prefix <= 714) return 'LA'
  if (prefix >= 716 && prefix <= 729) return 'AR'
  if (prefix >= 630 && prefix <= 658) return 'MO'
  if (prefix >= 500 && prefix <= 528) return 'IA'
  if (prefix >= 550 && prefix <= 567) return 'MN'
  if (prefix >= 530 && prefix <= 549) return 'WI'
  if (prefix >= 600 && prefix <= 629) return 'IL'
  if (prefix >= 460 && prefix <= 479) return 'IN'
  if (prefix >= 480 && prefix <= 499) return 'MI'
  if (prefix >= 430 && prefix <= 459) return 'OH'
  if (prefix >= 400 && prefix <= 427) return 'KY'
  if (prefix >= 370 && prefix <= 385) return 'TN'
  if (prefix >= 386 && prefix <= 397) return 'MS'
  if (prefix >= 350 && prefix <= 369) return 'AL'
  if (prefix >= 320 && prefix <= 339) return 'FL'
  if (prefix >= 300 && prefix <= 319) return 'GA'
  if (prefix >= 290 && prefix <= 299) return 'SC'
  if (prefix >= 270 && prefix <= 289) return 'NC'
  if (prefix >= 220 && prefix <= 246) return 'VA'
  if (prefix >= 247 && prefix <= 268) return 'WV'
  if (prefix >= 206 && prefix <= 219) return 'MD'
  if (prefix >= 200 && prefix <= 205) return 'DC'
  if (prefix >= 197 && prefix <= 199) return 'DE'
  if (prefix >= 150 && prefix <= 196) return 'PA'
  if (prefix >= 70 && prefix <= 89) return 'NJ'
  if (prefix >= 100 && prefix <= 149) return 'NY'
  if (prefix >= 60 && prefix <= 69) return 'CT'
  if (prefix >= 28 && prefix <= 29) return 'RI'
  if (prefix >= 10 && prefix <= 27) return 'MA'
  if (prefix >= 30 && prefix <= 38) return 'NH'
  if (prefix >= 39 && prefix <= 49) return 'ME'
  if (prefix >= 50 && prefix <= 59) return 'VT'

  return null
}

/**
 * Popular countries for international expansion
 * Ordered by likely early interest
 */
export const COUNTRIES = [
  { code: 'US', name: 'United States', flag: '🇺🇸' },
  { code: 'CA', name: 'Canada', flag: '🇨🇦' },
  { code: 'GB', name: 'United Kingdom', flag: '🇬🇧' },
  { code: 'AU', name: 'Australia', flag: '🇦🇺' },
  { code: 'DE', name: 'Germany', flag: '🇩🇪' },
  { code: 'FR', name: 'France', flag: '🇫🇷' },
  { code: 'ES', name: 'Spain', flag: '🇪🇸' },
  { code: 'IT', name: 'Italy', flag: '🇮🇹' },
  { code: 'NL', name: 'Netherlands', flag: '🇳🇱' },
  { code: 'BE', name: 'Belgium', flag: '🇧🇪' },
  { code: 'CH', name: 'Switzerland', flag: '🇨🇭' },
  { code: 'AT', name: 'Austria', flag: '🇦🇹' },
  { code: 'SE', name: 'Sweden', flag: '🇸🇪' },
  { code: 'NO', name: 'Norway', flag: '🇳🇴' },
  { code: 'DK', name: 'Denmark', flag: '🇩🇰' },
  { code: 'FI', name: 'Finland', flag: '🇫🇮' },
  { code: 'IE', name: 'Ireland', flag: '🇮🇪' },
  { code: 'PT', name: 'Portugal', flag: '🇵🇹' },
  { code: 'PL', name: 'Poland', flag: '🇵🇱' },
  { code: 'CZ', name: 'Czech Republic', flag: '🇨🇿' },
  { code: 'JP', name: 'Japan', flag: '🇯🇵' },
  { code: 'KR', name: 'South Korea', flag: '🇰🇷' },
  { code: 'SG', name: 'Singapore', flag: '🇸🇬' },
  { code: 'HK', name: 'Hong Kong', flag: '🇭🇰' },
  { code: 'NZ', name: 'New Zealand', flag: '🇳🇿' },
  { code: 'MX', name: 'Mexico', flag: '🇲🇽' },
  { code: 'BR', name: 'Brazil', flag: '🇧🇷' },
  { code: 'AR', name: 'Argentina', flag: '🇦🇷' },
  { code: 'CO', name: 'Colombia', flag: '🇨🇴' },
  { code: 'CL', name: 'Chile', flag: '🇨🇱' },
  { code: 'ZA', name: 'South Africa', flag: '🇿🇦' },
  { code: 'IN', name: 'India', flag: '🇮🇳' },
  { code: 'PH', name: 'Philippines', flag: '🇵🇭' },
  { code: 'ID', name: 'Indonesia', flag: '🇮🇩' },
  { code: 'MY', name: 'Malaysia', flag: '🇲🇾' },
  { code: 'TH', name: 'Thailand', flag: '🇹🇭' },
  { code: 'VN', name: 'Vietnam', flag: '🇻🇳' },
  { code: 'AE', name: 'United Arab Emirates', flag: '🇦🇪' },
  { code: 'SA', name: 'Saudi Arabia', flag: '🇸🇦' },
  { code: 'IL', name: 'Israel', flag: '🇮🇱' },
  { code: 'EG', name: 'Egypt', flag: '🇪🇬' },
  { code: 'NG', name: 'Nigeria', flag: '🇳🇬' },
  { code: 'KE', name: 'Kenya', flag: '🇰🇪' },
  { code: 'GH', name: 'Ghana', flag: '🇬🇭' },
]

/**
 * Get country name and flag from code
 */
export function getCountryInfo(countryCode: string): { name: string; flag: string } {
  const country = COUNTRIES.find((c) => c.code === countryCode)
  return country || { name: countryCode, flag: getCountryFlag(countryCode) }
}

/**
 * Official 77 Chicago Community Areas (per Chicago Data Portal)
 * These are fixed boundaries defined in the 1920s and used by the City of Chicago
 * for planning, data collection, and official statistics.
 *
 * Note: North Lawndale (#29) and South Lawndale (#30) are in zip codes 60623/60624
 */
export const CHICAGO_COMMUNITY_AREAS = [
  // Far North Side
  { id: 1, name: 'Rogers Park' },
  { id: 2, name: 'West Ridge' },
  { id: 77, name: 'Edgewater' },
  // North Side
  { id: 3, name: 'Uptown' },
  { id: 4, name: 'Lincoln Square' },
  { id: 5, name: 'North Center' },
  { id: 6, name: 'Lake View' },
  { id: 7, name: 'Lincoln Park' },
  // Near North
  { id: 8, name: 'Near North Side' },
  // Northwest Side
  { id: 9, name: 'Edison Park' },
  { id: 10, name: 'Norwood Park' },
  { id: 11, name: 'Jefferson Park' },
  { id: 12, name: 'Forest Glen' },
  { id: 13, name: 'North Park' },
  { id: 14, name: 'Albany Park' },
  { id: 76, name: "O'Hare" },
  // North West
  { id: 15, name: 'Portage Park' },
  { id: 16, name: 'Irving Park' },
  { id: 17, name: 'Dunning' },
  { id: 18, name: 'Montclare' },
  { id: 19, name: 'Belmont Cragin' },
  { id: 20, name: 'Hermosa' },
  // West Side
  { id: 21, name: 'Avondale' },
  { id: 22, name: 'Logan Square' },
  { id: 23, name: 'Humboldt Park' },
  { id: 24, name: 'West Town' },
  { id: 25, name: 'Austin' },
  { id: 26, name: 'West Garfield Park' },
  { id: 27, name: 'East Garfield Park' },
  { id: 28, name: 'Near West Side' },
  // *** LAUNCH AREA - North Lawndale (60623/60624) ***
  { id: 29, name: 'North Lawndale', isLaunchArea: true },
  { id: 30, name: 'South Lawndale', isLaunchArea: true }, // Little Village
  // Lower West
  { id: 31, name: 'Lower West Side' },
  // Central
  { id: 32, name: 'Loop' },
  { id: 33, name: 'Near South Side' },
  { id: 34, name: 'Armour Square' },
  // South Side
  { id: 35, name: 'Douglas' },
  { id: 36, name: 'Oakland' },
  { id: 37, name: 'Fuller Park' },
  { id: 38, name: 'Grand Boulevard' },
  { id: 39, name: 'Kenwood' },
  { id: 40, name: 'Washington Park' },
  { id: 41, name: 'Hyde Park' },
  { id: 42, name: 'Woodlawn' },
  { id: 43, name: 'South Shore' },
  // Far South Side
  { id: 44, name: 'Chatham' },
  { id: 45, name: 'Avalon Park' },
  { id: 46, name: 'South Chicago' },
  { id: 47, name: 'Burnside' },
  { id: 48, name: 'Calumet Heights' },
  { id: 49, name: 'Roseland' },
  { id: 50, name: 'Pullman' },
  { id: 51, name: 'South Deering' },
  { id: 52, name: 'East Side' },
  { id: 53, name: 'West Pullman' },
  { id: 54, name: 'Riverdale' },
  { id: 55, name: 'Hegewisch' },
  // Southwest Side
  { id: 56, name: 'Garfield Ridge' },
  { id: 57, name: 'Archer Heights' },
  { id: 58, name: 'Brighton Park' },
  { id: 59, name: 'McKinley Park' },
  { id: 60, name: 'Bridgeport' },
  { id: 61, name: 'New City' },
  { id: 62, name: 'West Elsdon' },
  { id: 63, name: 'Gage Park' },
  { id: 64, name: 'Clearing' },
  { id: 65, name: 'West Lawn' },
  { id: 66, name: 'Chicago Lawn' },
  { id: 67, name: 'West Englewood' },
  { id: 68, name: 'Englewood' },
  { id: 69, name: 'Greater Grand Crossing' },
  // Far Southwest Side
  { id: 70, name: 'Ashburn' },
  { id: 71, name: 'Auburn Gresham' },
  { id: 72, name: 'Beverly' },
  { id: 73, name: 'Washington Heights' },
  { id: 74, name: 'Mount Greenwood' },
  { id: 75, name: 'Morgan Park' },
] as const

// For backwards compatibility, export as simple string array
export const CHICAGO_NEIGHBORHOODS = CHICAGO_COMMUNITY_AREAS.map(ca => ca.name)

/**
 * Launch area zip codes (North Lawndale / South Lawndale)
 */
export const LAUNCH_ZIP_CODES = ['60623', '60624']

/**
 * Check if a zip code is in the launch area
 */
export function isLaunchAreaZip(zipCode: string): boolean {
  return LAUNCH_ZIP_CODES.includes(zipCode.trim())
}

/**
 * Continent mapping for global stats
 */
export const CONTINENTS = {
  'NA': { name: 'North America', emoji: '🌎' },
  'SA': { name: 'South America', emoji: '🌎' },
  'EU': { name: 'Europe', emoji: '🌍' },
  'AF': { name: 'Africa', emoji: '🌍' },
  'AS': { name: 'Asia', emoji: '🌏' },
  'OC': { name: 'Oceania', emoji: '🌏' },
  'AN': { name: 'Antarctica', emoji: '🌐' },
} as const

/**
 * Map country codes to continents
 */
export const COUNTRY_TO_CONTINENT: Record<string, keyof typeof CONTINENTS> = {
  // North America
  US: 'NA', CA: 'NA', MX: 'NA',
  // South America
  BR: 'SA', AR: 'SA', CO: 'SA', CL: 'SA',
  // Europe
  GB: 'EU', DE: 'EU', FR: 'EU', ES: 'EU', IT: 'EU', NL: 'EU', BE: 'EU',
  CH: 'EU', AT: 'EU', SE: 'EU', NO: 'EU', DK: 'EU', FI: 'EU', IE: 'EU',
  PT: 'EU', PL: 'EU', CZ: 'EU',
  // Asia
  JP: 'AS', KR: 'AS', SG: 'AS', HK: 'AS', IN: 'AS', PH: 'AS', ID: 'AS',
  MY: 'AS', TH: 'AS', VN: 'AS', AE: 'AS', SA: 'AS', IL: 'AS',
  // Africa
  ZA: 'AF', EG: 'AF', NG: 'AF', KE: 'AF', GH: 'AF',
  // Oceania
  AU: 'OC', NZ: 'OC',
}

/**
 * Get continent info from country code
 */
export function getContinent(countryCode: string): { code: string; name: string; emoji: string } {
  const continentCode = COUNTRY_TO_CONTINENT[countryCode] || 'NA'
  const continent = CONTINENTS[continentCode]
  return { code: continentCode, ...continent }
}
