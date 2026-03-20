/**
 * Chicago seeding data — density weights, tag distributions, and area metadata
 * for generating realistic community reports across all 77 community areas.
 */

// Density tiers based on real 311 complaint patterns
export type DensityTier = 'high' | 'medium' | 'low'

export interface AreaSeedConfig {
  number: number
  name: string
  density: DensityTier
  areaType: 'residential' | 'commercial' | 'industrial' | 'mixed'
  spotCount: [number, number] // [min, max] range
}

// Spot count ranges by tier
const HIGH: [number, number] = [40, 50]
const MED: [number, number] = [25, 35]
const LOW: [number, number] = [15, 25]

export const AREA_SEED_CONFIGS: AreaSeedConfig[] = [
  // HIGH density — areas with historically high 311 complaint volumes
  { number: 25, name: 'Austin', density: 'high', areaType: 'residential', spotCount: HIGH },
  { number: 68, name: 'Englewood', density: 'high', areaType: 'residential', spotCount: HIGH },
  { number: 26, name: 'West Garfield Park', density: 'high', areaType: 'residential', spotCount: HIGH },
  { number: 27, name: 'East Garfield Park', density: 'high', areaType: 'mixed', spotCount: HIGH },
  { number: 29, name: 'North Lawndale', density: 'high', areaType: 'residential', spotCount: HIGH },
  { number: 71, name: 'Auburn Gresham', density: 'high', areaType: 'residential', spotCount: HIGH },
  { number: 43, name: 'South Shore', density: 'high', areaType: 'residential', spotCount: HIGH },
  { number: 44, name: 'Chatham', density: 'high', areaType: 'residential', spotCount: HIGH },
  { number: 49, name: 'Roseland', density: 'high', areaType: 'residential', spotCount: HIGH },
  { number: 23, name: 'Humboldt Park', density: 'high', areaType: 'mixed', spotCount: HIGH },
  { number: 66, name: 'Chicago Lawn', density: 'high', areaType: 'residential', spotCount: HIGH },
  { number: 69, name: 'Greater Grand Crossing', density: 'high', areaType: 'residential', spotCount: HIGH },
  { number: 67, name: 'West Englewood', density: 'high', areaType: 'residential', spotCount: HIGH },
  { number: 61, name: 'New City', density: 'high', areaType: 'industrial', spotCount: HIGH },

  // MEDIUM density
  { number: 30, name: 'South Lawndale', density: 'medium', areaType: 'mixed', spotCount: MED },
  { number: 19, name: 'Belmont Cragin', density: 'medium', areaType: 'residential', spotCount: MED },
  { number: 58, name: 'Brighton Park', density: 'medium', areaType: 'mixed', spotCount: MED },
  { number: 63, name: 'Gage Park', density: 'medium', areaType: 'residential', spotCount: MED },
  { number: 46, name: 'South Chicago', density: 'medium', areaType: 'industrial', spotCount: MED },
  { number: 42, name: 'Woodlawn', density: 'medium', areaType: 'residential', spotCount: MED },
  { number: 38, name: 'Grand Boulevard', density: 'medium', areaType: 'mixed', spotCount: MED },
  { number: 40, name: 'Washington Park', density: 'medium', areaType: 'residential', spotCount: MED },
  { number: 56, name: 'Garfield Ridge', density: 'medium', areaType: 'residential', spotCount: MED },
  { number: 22, name: 'Logan Square', density: 'medium', areaType: 'mixed', spotCount: MED },
  { number: 24, name: 'West Town', density: 'medium', areaType: 'mixed', spotCount: MED },
  { number: 20, name: 'Hermosa', density: 'medium', areaType: 'residential', spotCount: MED },
  { number: 21, name: 'Avondale', density: 'medium', areaType: 'mixed', spotCount: MED },
  { number: 70, name: 'Ashburn', density: 'medium', areaType: 'residential', spotCount: MED },
  { number: 73, name: 'Washington Heights', density: 'medium', areaType: 'residential', spotCount: MED },
  { number: 51, name: 'South Deering', density: 'medium', areaType: 'industrial', spotCount: MED },
  { number: 53, name: 'West Pullman', density: 'medium', areaType: 'residential', spotCount: MED },
  { number: 48, name: 'Calumet Heights', density: 'medium', areaType: 'residential', spotCount: MED },
  { number: 47, name: 'Burnside', density: 'medium', areaType: 'residential', spotCount: MED },
  { number: 50, name: 'Pullman', density: 'medium', areaType: 'mixed', spotCount: MED },

  // LOW density — lower complaint areas
  { number: 1, name: 'Rogers Park', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 2, name: 'West Ridge', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 3, name: 'Uptown', density: 'low', areaType: 'mixed', spotCount: LOW },
  { number: 4, name: 'Lincoln Square', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 5, name: 'North Center', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 6, name: 'Lake View', density: 'low', areaType: 'commercial', spotCount: LOW },
  { number: 7, name: 'Lincoln Park', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 8, name: 'Near North Side', density: 'low', areaType: 'commercial', spotCount: LOW },
  { number: 9, name: 'Edison Park', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 10, name: 'Norwood Park', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 11, name: 'Jefferson Park', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 12, name: 'Forest Glen', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 13, name: 'North Park', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 14, name: 'Albany Park', density: 'low', areaType: 'mixed', spotCount: LOW },
  { number: 15, name: 'Portage Park', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 16, name: 'Irving Park', density: 'low', areaType: 'mixed', spotCount: LOW },
  { number: 17, name: 'Dunning', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 18, name: 'Montclare', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 28, name: 'Near West Side', density: 'low', areaType: 'mixed', spotCount: LOW },
  { number: 31, name: 'Lower West Side', density: 'low', areaType: 'mixed', spotCount: LOW },
  { number: 32, name: 'Loop', density: 'low', areaType: 'commercial', spotCount: LOW },
  { number: 33, name: 'Near South Side', density: 'low', areaType: 'commercial', spotCount: LOW },
  { number: 34, name: 'Armour Square', density: 'low', areaType: 'mixed', spotCount: LOW },
  { number: 35, name: 'Douglas', density: 'low', areaType: 'mixed', spotCount: LOW },
  { number: 36, name: 'Oakland', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 37, name: 'Fuller Park', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 39, name: 'Kenwood', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 41, name: 'Hyde Park', density: 'low', areaType: 'mixed', spotCount: LOW },
  { number: 45, name: 'Avalon Park', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 52, name: 'East Side', density: 'low', areaType: 'industrial', spotCount: LOW },
  { number: 54, name: 'Riverdale', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 55, name: 'Hegewisch', density: 'low', areaType: 'industrial', spotCount: LOW },
  { number: 57, name: 'Archer Heights', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 59, name: 'McKinley Park', density: 'low', areaType: 'mixed', spotCount: LOW },
  { number: 60, name: 'Bridgeport', density: 'low', areaType: 'mixed', spotCount: LOW },
  { number: 62, name: 'West Elsdon', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 64, name: 'Clearing', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 65, name: 'West Lawn', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 72, name: 'Beverly', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 74, name: 'Mount Greenwood', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 75, name: 'Morgan Park', density: 'low', areaType: 'residential', spotCount: LOW },
  { number: 76, name: "O'Hare", density: 'low', areaType: 'commercial', spotCount: LOW },
  { number: 77, name: 'Edgewater', density: 'low', areaType: 'residential', spotCount: LOW },
]

// Tag weights by area type
export const TAG_WEIGHTS: Record<string, Record<string, number>> = {
  residential: {
    trash: 30, leaves: 15, furniture: 15, clothing: 8, organic: 8,
    glass: 5, metal: 5, tires: 5, electronics: 4, construction: 3, hazardous: 2,
  },
  commercial: {
    trash: 35, glass: 12, metal: 8, construction: 10, electronics: 8,
    furniture: 7, organic: 6, clothing: 5, leaves: 4, tires: 3, hazardous: 2,
  },
  industrial: {
    construction: 25, metal: 18, tires: 12, trash: 15, hazardous: 8,
    glass: 5, electronics: 5, furniture: 5, organic: 3, leaves: 2, clothing: 2,
  },
  mixed: {
    trash: 28, furniture: 12, construction: 10, leaves: 10, glass: 8,
    metal: 7, clothing: 6, organic: 6, tires: 5, electronics: 5, hazardous: 3,
  },
}

// Severity descriptions by score range
export const SEVERITY_DESCRIPTIONS: Record<number, string[]> = {
  1: ['Minor litter, easily cleaned'],
  2: ['Small amount of scattered debris'],
  3: ['Moderate litter accumulation', 'A few pieces of dumped items'],
  4: ['Noticeable debris pile', 'Several bags of trash'],
  5: ['Significant dumping', 'Multiple large items'],
  6: ['Large debris field', 'Major illegal dumping'],
  7: ['Extensive dumping across area', 'Hazardous materials present'],
  8: ['Severe environmental hazard', 'Blocking pedestrian access'],
  9: ['Critical health hazard', 'Blocking vehicular access'],
  10: ['Emergency cleanup needed', 'Major public safety concern'],
}

/** Pick random int in [min, max] inclusive */
export function randInt(min: number, max: number): number {
  return Math.floor(Math.random() * (max - min + 1)) + min
}

/** Pick random float in [min, max] */
export function randFloat(min: number, max: number): number {
  return Math.random() * (max - min) + min
}

/** Normal distribution approximation (Box-Muller) */
export function normalRandom(mean: number, stddev: number): number {
  const u1 = Math.random()
  const u2 = Math.random()
  const z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)
  return Math.round(Math.max(1, Math.min(10, mean + z * stddev)))
}

/** Weighted random pick from a weight map */
export function weightedPick(weights: Record<string, number>): string {
  const total = Object.values(weights).reduce((a, b) => a + b, 0)
  let r = Math.random() * total
  for (const [key, weight] of Object.entries(weights)) {
    r -= weight
    if (r <= 0) return key
  }
  return Object.keys(weights)[0]
}

/** Pick N unique tags weighted by area type */
export function pickTags(areaType: string, count: number): string[] {
  const weights = { ...TAG_WEIGHTS[areaType] || TAG_WEIGHTS.mixed }
  const tags: string[] = []
  for (let i = 0; i < count; i++) {
    const tag = weightedPick(weights)
    if (!tags.includes(tag)) {
      tags.push(tag)
      delete weights[tag] // Don't pick same tag twice
    }
  }
  return tags
}

/** Generate a random timestamp over the last 6 months, weighted toward spring/summer */
export function randomTimestamp(): string {
  const now = new Date()
  const sixMonthsAgo = new Date(now.getTime() - 180 * 24 * 60 * 60 * 1000)

  // Weight toward recent months (spring/summer)
  const r = Math.random()
  const weight = r < 0.4 ? r * 0.5 : r // Compress older dates
  const ts = sixMonthsAgo.getTime() + weight * (now.getTime() - sixMonthsAgo.getTime())
  return new Date(ts).toISOString()
}
