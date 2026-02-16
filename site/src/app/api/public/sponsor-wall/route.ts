import { NextResponse } from 'next/server'
import { db } from '@/db'
import { transactions, sponsors } from '@/db/schema'
import { eq, desc, sql, sum, count } from 'drizzle-orm'

export const runtime = 'nodejs'
export const revalidate = 60 // Revalidate every 60 seconds

// Tier thresholds in cents
const TIER_THRESHOLDS = {
  platinum: 1000000, // $10,000+
  gold: 250000,      // $2,500+
  silver: 50000,     // $500+
  bronze: 0,         // < $500
}

function getTier(amountCents: number): 'platinum' | 'gold' | 'silver' | 'bronze' {
  if (amountCents >= TIER_THRESHOLDS.platinum) return 'platinum'
  if (amountCents >= TIER_THRESHOLDS.gold) return 'gold'
  if (amountCents >= TIER_THRESHOLDS.silver) return 'silver'
  return 'bronze'
}

export async function GET() {
  try {
    // Query transactions directly to get accurate sponsor data
    // Aggregate by sponsorId only (not displayName) to consolidate accounts
    const sponsorData = await db
      .select({
        sponsorId: transactions.sponsorId,
        totalAmount: sum(transactions.amount),
        blocksSponsored: count(sql`CASE WHEN ${transactions.blockId} IS NOT NULL THEN 1 END`),
        lastContribution: sql<string>`MAX(${transactions.createdAt})`,
        // Collect all unique display names used by this sponsor
        displayNames: sql<string>`ARRAY_AGG(DISTINCT ${transactions.displayName}) FILTER (WHERE ${transactions.displayName} IS NOT NULL)`,
        // Also get sponsor table data for fallback names
        sponsorName: sponsors.name,
        businessName: sponsors.businessName,
        sponsorType: sponsors.type,
        preferencesIsAnonymous: sponsors.preferencesIsAnonymous,
      })
      .from(transactions)
      .leftJoin(sponsors, eq(transactions.sponsorId, sponsors.id))
      .where(eq(transactions.status, 'completed'))
      .groupBy(
        transactions.sponsorId,
        sponsors.name,
        sponsors.businessName,
        sponsors.type,
        sponsors.preferencesIsAnonymous
      )
      .orderBy(desc(sum(transactions.amount)))
      .limit(100)

    const sponsorsList = sponsorData.map((s) => {
      const totalContributed = Number(s.totalAmount) || 0
      const blocksFunded = Number(s.blocksSponsored) || 0
      // Only use sponsor-level anonymous preference (transaction-level is per-donation)
      const isAnonymous = s.preferencesIsAnonymous === true

      // Parse display names array (PostgreSQL returns as string or array)
      let displayNames: string[] = []
      if (s.displayNames) {
        if (Array.isArray(s.displayNames)) {
          displayNames = s.displayNames.filter(Boolean)
        } else if (typeof s.displayNames === 'string') {
          // Parse PostgreSQL array format: {name1,name2}
          displayNames = s.displayNames
            .replace(/^\{|\}$/g, '')
            .split(',')
            .map(n => n.replace(/^"|"$/g, '').trim())
            .filter(Boolean)
        }
      }

      // Determine primary display name - prefer display names from donations first
      let name: string
      if (isAnonymous) {
        name = 'Anonymous Sponsor'
      } else if (displayNames.length > 0) {
        name = displayNames[0] // Use first donation display name as primary
      } else if (s.businessName) {
        name = s.businessName
      } else if (s.sponsorName) {
        name = s.sponsorName
      } else {
        name = 'Anonymous Sponsor'
      }

      // AKAs are other display names (excluding the primary)
      // Never show AKAs for anonymous sponsors
      const akas = isAnonymous ? [] : displayNames.slice(1)

      return {
        id: s.sponsorId,
        name,
        akas, // Other names used by this account
        tier: getTier(totalContributed),
        blocksSponsored: blocksFunded,
        totalContribution: totalContributed, // Keep in cents - formatCurrency converts
        date: s.lastContribution,
        isAnonymous,
        type: s.sponsorType,
      }
    })

    // Get aggregate stats from transactions
    const [statsResult] = await db
      .select({
        totalRaised: sum(transactions.amount),
        totalBlocks: count(sql`CASE WHEN ${transactions.blockId} IS NOT NULL THEN 1 END`),
        sponsorCount: count(sql`DISTINCT ${transactions.sponsorId}`),
      })
      .from(transactions)
      .where(eq(transactions.status, 'completed'))

    // Get recent contributions with messages for the activity feed
    const recentContributions = await db
      .select({
        id: transactions.id,
        amount: transactions.amount,
        displayName: transactions.displayName,
        isAnonymous: transactions.isAnonymous,
        message: transactions.message,
        messageStatus: transactions.messageStatus,
        messageRedactedText: transactions.messageRedactedText,
        createdAt: transactions.createdAt,
        sponsorName: sponsors.name,
        businessName: sponsors.businessName,
      })
      .from(transactions)
      .leftJoin(sponsors, eq(transactions.sponsorId, sponsors.id))
      .where(eq(transactions.status, 'completed'))
      .orderBy(desc(transactions.createdAt))
      .limit(20)

    const recentList = recentContributions.map((t) => {
      const isAnonymous = t.isAnonymous ?? false
      const messageStatus = t.messageStatus || 'visible'

      // Determine display name
      let name: string
      if (isAnonymous) {
        name = 'Anonymous Supporter'
      } else if (t.displayName) {
        name = t.displayName
      } else if (t.businessName) {
        name = t.businessName
      } else if (t.sponsorName) {
        name = t.sponsorName
      } else {
        name = 'Anonymous Supporter'
      }

      // Handle message based on moderation status
      let message: string | null = null
      if (!isAnonymous && messageStatus !== 'hidden') {
        if (messageStatus === 'redacted' && t.messageRedactedText) {
          message = t.messageRedactedText
        } else if (messageStatus === 'visible') {
          message = t.message
        }
      }

      return {
        id: t.id,
        name,
        amount: Number(t.amount) || 0, // Keep in cents
        message,
        isAnonymous,
        createdAt: t.createdAt,
      }
    })

    return NextResponse.json({
      sponsors: sponsorsList,
      recent: recentList,
      stats: {
        totalBlocks: Number(statsResult?.totalBlocks) || 0,
        totalRaised: Number(statsResult?.totalRaised) || 0, // Keep in cents - formatCurrency converts
        sponsorCount: Number(statsResult?.sponsorCount) || 0,
      },
    })
  } catch (error) {
    console.error('Sponsor wall error:', error)
    return NextResponse.json({
      sponsors: [],
      stats: { totalBlocks: 0, totalRaised: 0, sponsorCount: 0 },
    })
  }
}
