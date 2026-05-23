/**
 * Seed script for GCC database
 *
 * Creates test data for development.
 * Run with: npm run seed
 */

import { db } from '../db'
import { workers, sponsors, blocks, waitlistEntries, serviceAreas } from '../db/schema'
import { hashPassword } from '../lib/auth'
import { generateReferralCode, getEarlyAdopterTier } from '../lib/movement-rankings'
import { getChicagoCommunityArea } from '../lib/chicago-community-areas'
import { eq } from 'drizzle-orm'
import { NORTH_LAWNDALE_BLOCKS, MAP_CENTER, MAP_BOUNDS } from '../data/blocks'
import { generateBlockCheckpointsFromGeometry, MIN_ROUTE_CHECKPOINTS } from '../lib/route-checkpoints'

// North Lawndale service area (for foreign key reference)
const NORTH_LAWNDALE_SERVICE_AREA = {
  name: 'North Lawndale',
  city: 'Chicago',
  state: 'IL',
  boundary: {
    type: 'Polygon' as const,
    coordinates: [[
      [MAP_BOUNDS.west, MAP_BOUNDS.south],
      [MAP_BOUNDS.east, MAP_BOUNDS.south],
      [MAP_BOUNDS.east, MAP_BOUNDS.north],
      [MAP_BOUNDS.west, MAP_BOUNDS.north],
      [MAP_BOUNDS.west, MAP_BOUNDS.south], // Close the ring
    ]],
  },
  mapCenter: [MAP_CENTER.longitude, MAP_CENTER.latitude] as [number, number],
  defaultZoom: String(MAP_CENTER.zoom),
  status: 'active' as const,
  launchDate: new Date().toISOString(),
}

// Test workers
const SEED_WORKERS = [
  {
    name: 'Marcus Johnson',
    email: 'marcus.j@example.com',
    phone: '3125551001',
    addressStreet: '3400 W Roosevelt Rd',
    addressCity: 'Chicago',
    addressState: 'IL',
    addressZip: '60624',
    emailVerified: true,
    verificationStatus: 'approved' as const,
    statsJobsCompleted: '25',
    statsTotalEarnings: '250000',
  },
  {
    name: 'Aaliyah Williams',
    email: 'aaliyah.w@example.com',
    phone: '3125551002',
    addressStreet: '3200 W Ogden Ave',
    addressCity: 'Chicago',
    addressState: 'IL',
    addressZip: '60623',
    emailVerified: true,
    verificationStatus: 'approved' as const,
    statsJobsCompleted: '12',
    statsTotalEarnings: '120000',
  },
  {
    name: 'Keisha Brown',
    email: 'keisha.b@example.com',
    phone: '3125551004',
    addressStreet: '3300 W 16th St',
    addressCity: 'Chicago',
    addressState: 'IL',
    addressZip: '60623',
    emailVerified: true,
    verificationStatus: 'pending' as const,
    statsJobsCompleted: '0',
    statsTotalEarnings: '0',
  },
]

// Test sponsors
const SEED_SPONSORS = [
  {
    name: 'Westside Hardware',
    email: 'sarah.m@westside-hardware.com',
    type: 'business' as const,
    businessName: 'Westside Hardware',
    statsTotalContributed: '100000',
    statsBlocksFunded: '10',
  },
  {
    name: 'James Wilson',
    email: 'james.wilson@gmail.com',
    type: 'individual' as const,
    statsTotalContributed: '50000',
    statsBlocksFunded: '5',
  },
]

// Test waitlist entries
const SEED_WAITLIST = [
  {
    name: 'David Martinez',
    email: 'david.m@example.com',
    phone: '3125552001',
    locationCity: 'Chicago',
    locationState: 'Illinois',
    locationStateCode: 'IL',
    locationCountry: 'United States',
    locationCountryCode: 'US',
    locationNeighborhood: 'North Lawndale',
  },
  {
    name: 'Michelle Garcia',
    email: 'michelle.g@example.com',
    phone: '3125552002',
    locationCity: 'Los Angeles',
    locationState: 'California',
    locationStateCode: 'CA',
    locationCountry: 'United States',
    locationCountryCode: 'US',
  },
  {
    name: 'Robert Taylor',
    email: 'robert.t@example.com',
    phone: '3125552003',
    locationCity: 'New York',
    locationState: 'New York',
    locationStateCode: 'NY',
    locationCountry: 'United States',
    locationCountryCode: 'US',
  },
]

async function seed() {
  console.log('🌱 Starting GCC seed...\n')

  // Seed service area first
  console.log('🗺️ Creating service area...')
  let serviceAreaId: number

  const [existingArea] = await db
    .select({ id: serviceAreas.id })
    .from(serviceAreas)
    .where(eq(serviceAreas.name, NORTH_LAWNDALE_SERVICE_AREA.name))
    .limit(1)

  if (existingArea) {
    console.log(`   ℹ Service area "${NORTH_LAWNDALE_SERVICE_AREA.name}" already exists`)
    serviceAreaId = existingArea.id
  } else {
    const [newArea] = await db.insert(serviceAreas).values(NORTH_LAWNDALE_SERVICE_AREA).returning()
    serviceAreaId = newArea.id
    console.log(`   ✅ ${NORTH_LAWNDALE_SERVICE_AREA.name}`)
  }

  // Seed the 10 blocks from hardcoded data with full geometry
  console.log('\n🏘️ Creating blocks with geometry...')
  for (const blockDef of NORTH_LAWNDALE_BLOCKS) {
    const [existing] = await db
      .select({ id: blocks.id })
      .from(blocks)
      .where(eq(blocks.code, blockDef.code))
      .limit(1)

    if (existing) {
      console.log(`   ℹ Block "${blockDef.code}" already exists`)
      continue
    }

    const blockData = {
      code: blockDef.code,
      name: `${blockDef.streetName} - ${blockDef.fromCorner} to ${blockDef.toCorner}`,
      description: `${blockDef.side === 'north' ? 'North' : 'South'} side of Roosevelt Road, ${blockDef.includesAlley ? 'including alley' : 'sidewalk only'}`,
      status: 'funded' as const,
      serviceAreaId,
      locationNeighborhood: 'North Lawndale',
      locationBoundary: blockDef.geo.polygon,
      locationCenter: blockDef.geo.center,
      streetName: blockDef.streetName,
      fromCorner: blockDef.fromCorner,
      toCorner: blockDef.toCorner,
      side: blockDef.side,
      includesAlley: blockDef.includesAlley,
      checkpoints: blockDef.checkpoints.length >= MIN_ROUTE_CHECKPOINTS
        ? blockDef.checkpoints
        : generateBlockCheckpointsFromGeometry(blockDef.geo.polygon),
      fundingGoal: '10000', // $100 in cents
    }

    await db.insert(blocks).values(blockData)
    console.log(`   ✅ ${blockDef.code}: ${blockDef.fromCorner} → ${blockDef.toCorner} (${blockDef.side})`)
  }

  // Seed workers
  console.log('\n👷 Creating workers...')
  for (const workerData of SEED_WORKERS) {
    const [existing] = await db
      .select({ id: workers.id })
      .from(workers)
      .where(eq(workers.email, workerData.email))
      .limit(1)

    if (existing) {
      console.log(`   ℹ Worker "${workerData.name}" already exists`)
      continue
    }

    const { hash, salt } = hashPassword('TestPassword123!')
    const referralCode = generateReferralCode()

    // Count for ranking
    const [countResult] = await db.select().from(workers)
    const globalRank = (countResult ? 1 : 0) + 1

    // Get community area from zip code
    const communityArea = getChicagoCommunityArea(workerData.addressZip) || 'North Lawndale'

    await db.insert(workers).values({
      ...workerData,
      hash,
      salt,
      referralCode,
      addressNeighborhood: communityArea,
      signupRankingsGlobal: String(globalRank),
      signupRankingsCity: String(globalRank),
      signupRankingsState: String(globalRank),
      signupRankingsCountry: String(globalRank),
      signupRankingsNeighborhood: String(globalRank),
      earlyAdopterTier: getEarlyAdopterTier(globalRank),
      signupTimestamp: new Date().toISOString(),
    })
    console.log(`   ✅ ${workerData.name} (${workerData.verificationStatus})`)
  }

  // Seed sponsors
  console.log('\n💚 Creating sponsors...')
  for (const sponsorData of SEED_SPONSORS) {
    const [existing] = await db
      .select({ id: sponsors.id })
      .from(sponsors)
      .where(eq(sponsors.email, sponsorData.email))
      .limit(1)

    if (existing) {
      console.log(`   ℹ Sponsor "${sponsorData.name}" already exists`)
      continue
    }

    const { hash, salt } = hashPassword('TestPassword123!')
    const referralCode = generateReferralCode()

    // Count all participants for ranking
    const [{ count }] = await db.select({ count: db.$count(workers) }).from(workers)
    const [{ wlCount }] = await db.select({ wlCount: db.$count(waitlistEntries) }).from(waitlistEntries)
    const [{ spCount }] = await db.select({ spCount: db.$count(sponsors) }).from(sponsors)
    const globalRank = Number(count || 0) + Number(wlCount || 0) + Number(spCount || 0) + 1

    await db.insert(sponsors).values({
      ...sponsorData,
      hash,
      salt,
      referralCode,
      signupTimestamp: new Date().toISOString(),
      signupRankingsGlobal: String(globalRank),
      earlyAdopterTier: getEarlyAdopterTier(globalRank),
    })
    console.log(`   ✅ ${sponsorData.name} (${sponsorData.type})`)
  }

  // Seed waitlist
  console.log('\n📋 Creating waitlist entries...')
  for (let i = 0; i < SEED_WAITLIST.length; i++) {
    const entry = SEED_WAITLIST[i]
    const [existing] = await db
      .select({ id: waitlistEntries.id })
      .from(waitlistEntries)
      .where(eq(waitlistEntries.email, entry.email))
      .limit(1)

    if (existing) {
      console.log(`   ℹ Waitlist entry "${entry.name}" already exists`)
      continue
    }

    const referralCode = generateReferralCode()
    const globalRank = i + 1

    await db.insert(waitlistEntries).values({
      ...entry,
      referralCode,
      rankingsGlobal: String(globalRank),
      rankingsCity: String(globalRank),
      rankingsState: String(globalRank),
      rankingsCountry: String(globalRank),
      earlyAdopterTier: getEarlyAdopterTier(globalRank),
      signupTimestamp: new Date().toISOString(),
    })
    console.log(`   ✅ ${entry.name} (${entry.locationCity})`)
  }

  console.log('\n✨ Seed completed!')
  console.log('\n🧪 Test Accounts:')
  console.log('   Workers:')
  console.log('     - marcus.j@example.com (password: TestPassword123!)')
  console.log('     - aaliyah.w@example.com (password: TestPassword123!)')
  console.log('   Sponsors:')
  console.log('     - sarah.m@westside-hardware.com (password: TestPassword123!)')
  console.log('     - james.wilson@gmail.com (password: TestPassword123!)')

  process.exit(0)
}

seed().catch((error) => {
  console.error('Seed failed:', error)
  process.exit(1)
})
