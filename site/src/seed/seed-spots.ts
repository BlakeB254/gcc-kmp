/**
 * Seed ~2,000+ community reports across all 77 Chicago community areas.
 *
 * Usage: cd site && npx tsx src/seed/seed-spots.ts
 *
 * Requires DATABASE_URL in .env.local
 */

import * as dotenv from 'dotenv'
dotenv.config({ path: '.env.local' })
dotenv.config() // fallback to .env

import { neon } from '@neondatabase/serverless'
import { drizzle } from 'drizzle-orm/neon-http'
import { communityReports } from '../db/schema'
import { sql } from 'drizzle-orm'
import { getRandomGridPoints, getAreaDisplayName } from './chicago-grid'
import { getPrimaryWard, getPoliceDistrict } from '../lib/chicago-geo-data'
import {
  AREA_SEED_CONFIGS,
  TAG_WEIGHTS,
  SEVERITY_DESCRIPTIONS,
  randInt,
  randFloat,
  normalRandom,
  pickTags,
  randomTimestamp,
  weightedPick,
} from './chicago-seed-data'

const databaseUrl = process.env.DATABASE_URL || process.env.DATABASE_URI
if (!databaseUrl) {
  console.error('ERROR: DATABASE_URL not set. Run from site/ with .env.local present.')
  process.exit(1)
}

const sqlClient = neon(databaseUrl)
const db = drizzle(sqlClient)

// Chicago zip codes by community area number (approximate primary zip)
const AREA_ZIPS: Record<number, string> = {
  1: '60626', 2: '60645', 3: '60640', 4: '60625', 5: '60618',
  6: '60657', 7: '60614', 8: '60610', 9: '60631', 10: '60631',
  11: '60630', 12: '60646', 13: '60625', 14: '60625', 15: '60641',
  16: '60618', 17: '60634', 18: '60707', 19: '60639', 20: '60639',
  21: '60618', 22: '60647', 23: '60651', 24: '60622', 25: '60644',
  26: '60624', 27: '60612', 28: '60607', 29: '60623', 30: '60623',
  31: '60608', 32: '60601', 33: '60605', 34: '60616', 35: '60616',
  36: '60653', 37: '60609', 38: '60615', 39: '60615', 40: '60637',
  41: '60637', 42: '60637', 43: '60649', 44: '60619', 45: '60619',
  46: '60617', 47: '60619', 48: '60617', 49: '60628', 50: '60628',
  51: '60617', 52: '60617', 53: '60643', 54: '60827', 55: '60633',
  56: '60638', 57: '60632', 58: '60632', 59: '60608', 60: '60608',
  61: '60609', 62: '60638', 63: '60629', 64: '60638', 65: '60629',
  66: '60629', 67: '60636', 68: '60621', 69: '60619', 70: '60652',
  71: '60620', 72: '60643', 73: '60643', 74: '60655', 75: '60643',
  76: '60666', 77: '60660',
}

// Chicago street names for generating realistic addresses
const NS_STREETS = [
  'Western Ave', 'Ashland Ave', 'Halsted St', 'State St', 'Michigan Ave',
  'Pulaski Rd', 'Cicero Ave', 'Central Ave', 'Kedzie Ave', 'California Ave',
  'Sacramento Blvd', 'Damen Ave', 'Racine Ave', 'Leavitt St', 'Oakley Ave',
  'Kostner Ave', 'Laramie Ave', 'Austin Blvd', 'Stony Island Ave', 'Cottage Grove Ave',
  'King Dr', 'Vincennes Ave', 'Wentworth Ave', 'Stewart Ave', 'Normal Ave',
]
const EW_STREETS = [
  'Madison St', 'Chicago Ave', 'Division St', 'North Ave', 'Fullerton Ave',
  '63rd St', '79th St', '87th St', '95th St', '103rd St', '111th St',
  'Roosevelt Rd', 'Cermak Rd', '31st St', '35th St', '47th St', '51st St',
  'Garfield Blvd', 'Marquette Rd', '71st St', 'Pershing Rd', 'Archer Ave',
  'Irving Park Rd', 'Belmont Ave', 'Diversey Ave', 'Addison St', 'Lawrence Ave',
]

function randomAddress(): string {
  const num = randInt(1, 99) * 100 + randInt(0, 99)
  const street = Math.random() < 0.5
    ? NS_STREETS[randInt(0, NS_STREETS.length - 1)]
    : EW_STREETS[randInt(0, EW_STREETS.length - 1)]
  const dir = Math.random() < 0.5 ? 'N' : 'S'
  return `${num} ${dir} ${street}`
}

// AI analysis templates
function generateAiAnalysis(tags: string[], severity: number): any {
  const categories: Record<string, number> = {}
  for (const tag of tags) {
    categories[tag] = randFloat(0.6, 0.98)
  }
  return {
    categories,
    severityScore: severity,
    estimatedWeightLbs: Math.round(severity * randFloat(3, 8)),
    estimatedVolumeCuFt: Math.round(severity * randFloat(0.5, 2) * 10) / 10,
    confidence: randFloat(0.75, 0.95),
    modelVersion: '1.2.0',
  }
}

// Depth analysis templates
function generateDepthAnalysis(): any {
  return {
    hasDepthData: true,
    volumeEstimateCuFt: randFloat(0.5, 15),
    maxDepthMeters: randFloat(0.1, 1.5),
    surfaceAreaSqM: randFloat(0.5, 8),
    deviceModel: ['iPhone 15 Pro', 'iPhone 14 Pro', 'iPad Pro'][randInt(0, 2)],
  }
}

// Description templates
const SIGHTING_DESCS = [
  'Pile of trash bags dumped on the sidewalk',
  'Old furniture left on the curb for weeks',
  'Broken glass and debris near the intersection',
  'Illegal dumping along the alley fence',
  'Scattered litter and food waste near the bus stop',
  'Construction debris dumped behind the building',
  'Mattress and box springs blocking the sidewalk',
  'Bags of yard waste left on the parkway',
  'Tires dumped in the vacant lot',
  'Overflowing dumpster with trash spilling onto the street',
  'Electronics and appliances dumped near the tracks',
  'Pile of clothing and household items on the corner',
  'Broken bottles and cans along the fence line',
  'Large pile of mixed debris in the alley',
  'Abandoned furniture and trash near the school',
]

const CLEANUP_DESCS = [
  'Cleaned up litter and debris from the block',
  'Removed dumped furniture and bagged trash',
  'Cleared broken glass and swept the sidewalk',
  'Bagged and removed alley debris',
  'Volunteer cleanup of the vacant lot',
  'Community cleanup — collected 8 bags of trash',
  'Removed tires and construction waste from the lot',
  'Swept and cleaned the bus stop area',
  'Cleared litter from the park entrance',
  'Removed dumped items from the parkway',
]

async function main() {
  console.log('🌱 Seeding community reports across 77 Chicago community areas...\n')

  let totalInserted = 0
  const batchSize = 50

  for (const config of AREA_SEED_CONFIGS) {
    const count = randInt(config.spotCount[0], config.spotCount[1])
    const coords = getRandomGridPoints(config.number, count)
    const displayName = getAreaDisplayName(config.number)
    const ward = getPrimaryWard(config.number)
    const policeDistrict = getPoliceDistrict(config.number)
    const zipCode = AREA_ZIPS[config.number] || '60601'

    console.log(`  #${config.number} ${displayName}: ${count} spots (${config.density})`)

    // Build all values for this area
    const values: any[] = []
    for (let i = 0; i < coords.length; i++) {
      const [lat, lng] = coords[i]

      // Type distribution: 70% sighting, 15% volunteer_cleanup, 15% verified sighting
      const typeRoll = Math.random()
      const type = typeRoll < 0.70 ? 'sighting' : 'volunteer_cleanup'
      const isVerified = typeRoll >= 0.85

      // Severity: normal distribution centered at 5, stddev 2
      const severity = normalRandom(5, 2)

      // Tags: 1-4 tags weighted by area type
      const tagCount = randInt(1, 4)
      const tags = pickTags(config.areaType, tagCount)

      // Description
      const description = type === 'sighting'
        ? SIGHTING_DESCS[randInt(0, SIGHTING_DESCS.length - 1)]
        : CLEANUP_DESCS[randInt(0, CLEANUP_DESCS.length - 1)]

      // Timestamp (spread over 6 months, weighted toward spring/summer)
      const createdAt = randomTimestamp()
      const updatedAt = createdAt

      // Status
      const status = isVerified ? 'verified' : (Math.random() < 0.8 ? 'pending' : 'acknowledged')

      // AI analysis on ~60%
      const hasAi = Math.random() < 0.6
      const aiResult = hasAi ? generateAiAnalysis(tags, severity) : null

      // LiDAR depth on ~20%
      const hasDepth = Math.random() < 0.2
      const depthData = hasDepth ? generateDepthAnalysis() : null

      // Affected radius
      const radius = randInt(5, 50)

      // Verification fields
      const verificationCount = isVerified ? randInt(1, 5) : 0
      const lastVerifiedAt = isVerified ? createdAt : null
      const nextVerificationDue = isVerified
        ? new Date(new Date(createdAt).getTime() + 24 * 60 * 60 * 1000).toISOString()
        : null

      // Cleanup details
      const cleanupDuration = type === 'volunteer_cleanup' ? randInt(15, 120) : null
      const cleanupBags = type === 'volunteer_cleanup' ? randInt(1, 12) : null

      values.push({
        reporterType: 'worker' as const,
        reporterId: randInt(1, 5), // Reference existing workers
        reporterName: ['Marcus J.', 'Tamika R.', 'Devon W.', 'Angela M.', 'Carlos P.'][randInt(0, 4)],
        type,
        description,
        coordinates: [lng, lat], // GeoJSON [lng, lat]
        tags,
        status,
        affectedRadiusMeters: String(radius),
        showOnMap: true,
        capturedAt: createdAt,
        createdAt,
        updatedAt,
        onDeviceAiResult: aiResult,
        depthAnalysis: depthData,
        verificationCount,
        lastVerifiedAt,
        nextVerificationDue,
        missedVerifications: 0,
        cleanupDurationMinutes: cleanupDuration,
        cleanupBagsCollected: cleanupBags,
        viewCount: randInt(0, 50),
        thankCount: randInt(0, 15),
        // Chicago geo fields
        geoCity: 'Chicago',
        geoState: 'Illinois',
        geoCommunityArea: displayName,
        geoCommunityAreaNumber: config.number,
        geoWard: ward,
        geoZipCode: zipCode,
        geoPoliceDistrict: policeDistrict,
        geoStreetAddress: randomAddress(),
      })
    }

    // Insert in batches
    for (let i = 0; i < values.length; i += batchSize) {
      const batch = values.slice(i, i + batchSize)
      await db.insert(communityReports).values(batch)
    }

    totalInserted += values.length
  }

  console.log(`\n✅ Seeded ${totalInserted} community reports across 77 community areas.`)

  // Print summary stats
  const [stats] = await db.execute(sql`
    SELECT
      COUNT(*) as total,
      COUNT(DISTINCT geo_community_area) as areas,
      COUNT(CASE WHEN status = 'pending' THEN 1 END) as pending,
      COUNT(CASE WHEN status = 'verified' THEN 1 END) as verified,
      COUNT(CASE WHEN type = 'sighting' THEN 1 END) as sightings,
      COUNT(CASE WHEN type = 'volunteer_cleanup' THEN 1 END) as cleanups,
      COUNT(CASE WHEN on_device_ai_result IS NOT NULL THEN 1 END) as with_ai,
      COUNT(CASE WHEN depth_analysis IS NOT NULL THEN 1 END) as with_lidar
    FROM community_reports
  `)
  console.log('\n📊 Database totals:')
  console.log(stats)
}

main().catch(console.error)
