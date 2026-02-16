import { NextResponse } from 'next/server'
import { db } from '@/db'
import { waitlistEntries, workers } from '@/db/schema'
import { eq, sql } from 'drizzle-orm'

export const runtime = 'nodejs'

const TIER_THRESHOLDS = {
  founder: { max: 100, label: 'Founder', emoji: '👑' },
  pioneer: { max: 1000, label: 'Pioneer', emoji: '🚀' },
  early: { max: 10000, label: 'Early Adopter', emoji: '⭐' },
}

export async function GET() {
  try {
    // Get counts
    const [{ waitlistCount }] = await db
      .select({ waitlistCount: sql<number>`count(*)` })
      .from(waitlistEntries)

    const [{ workersCount }] = await db
      .select({ workersCount: sql<number>`count(*)` })
      .from(workers)

    const [{ chicagoWaitlist }] = await db
      .select({ chicagoWaitlist: sql<number>`count(*)` })
      .from(waitlistEntries)
      .where(eq(waitlistEntries.locationCity, 'Chicago'))

    const [{ chicagoWorkers }] = await db
      .select({ chicagoWorkers: sql<number>`count(*)` })
      .from(workers)
      .where(eq(workers.addressCity, 'Chicago'))

    const total = Number(waitlistCount) + Number(workersCount)
    const chicago = Number(chicagoWaitlist) + Number(chicagoWorkers)

    // Calculate tier counts
    const [{ founderCount }] = await db
      .select({ founderCount: sql<number>`count(*)` })
      .from(waitlistEntries)
      .where(eq(waitlistEntries.earlyAdopterTier, 'founder'))

    const [{ founderWorkers }] = await db
      .select({ founderWorkers: sql<number>`count(*)` })
      .from(workers)
      .where(eq(workers.earlyAdopterTier, 'founder'))

    const [{ pioneerCount }] = await db
      .select({ pioneerCount: sql<number>`count(*)` })
      .from(waitlistEntries)
      .where(eq(waitlistEntries.earlyAdopterTier, 'pioneer'))

    const [{ pioneerWorkers }] = await db
      .select({ pioneerWorkers: sql<number>`count(*)` })
      .from(workers)
      .where(eq(workers.earlyAdopterTier, 'pioneer'))

    const [{ earlyCount }] = await db
      .select({ earlyCount: sql<number>`count(*)` })
      .from(waitlistEntries)
      .where(eq(waitlistEntries.earlyAdopterTier, 'early'))

    const [{ earlyWorkers }] = await db
      .select({ earlyWorkers: sql<number>`count(*)` })
      .from(workers)
      .where(eq(workers.earlyAdopterTier, 'early'))

    const founderTotal = Number(founderCount) + Number(founderWorkers)
    const pioneerTotal = Number(pioneerCount) + Number(pioneerWorkers)
    const earlyTotal = Number(earlyCount) + Number(earlyWorkers)

    return NextResponse.json({
      success: true,
      stats: {
        total,
        waitlist: Number(waitlistCount),
        workers: Number(workersCount),
        chicago,
        tiers: {
          founder: {
            total: founderTotal,
            remaining: Math.max(0, TIER_THRESHOLDS.founder.max - founderTotal),
            label: TIER_THRESHOLDS.founder.label,
            emoji: TIER_THRESHOLDS.founder.emoji,
          },
          pioneer: {
            total: pioneerTotal,
            remaining: Math.max(0, TIER_THRESHOLDS.pioneer.max - total),
            label: TIER_THRESHOLDS.pioneer.label,
            emoji: TIER_THRESHOLDS.pioneer.emoji,
          },
          early: {
            total: earlyTotal,
            remaining: Math.max(0, TIER_THRESHOLDS.early.max - total),
            label: TIER_THRESHOLDS.early.label,
            emoji: TIER_THRESHOLDS.early.emoji,
          },
        },
        milestones: {
          hundred: { current: total, target: 100, complete: total >= 100 },
          thousand: { current: total, target: 1000, complete: total >= 1000 },
          tenThousand: { current: total, target: 10000, complete: total >= 10000 },
        },
      },
    })
  } catch (error) {
    console.error('Movement stats error:', error)
    return NextResponse.json({
      success: true,
      stats: {
        total: 0,
        waitlist: 0,
        workers: 0,
        chicago: 0,
        tiers: {
          founder: { total: 0, remaining: 100, label: 'Founder', emoji: '👑' },
          pioneer: { total: 0, remaining: 1000, label: 'Pioneer', emoji: '🚀' },
          early: { total: 0, remaining: 10000, label: 'Early Adopter', emoji: '⭐' },
        },
        milestones: {
          hundred: { current: 0, target: 100, complete: false },
          thousand: { current: 0, target: 1000, complete: false },
          tenThousand: { current: 0, target: 10000, complete: false },
        },
      },
    })
  }
}
