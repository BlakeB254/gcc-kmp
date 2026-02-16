import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { transactions, sponsors, blocks } from '@/db/schema'
import { eq, desc, isNotNull, sql, and, sum, count } from 'drizzle-orm'

export const runtime = 'nodejs'

/**
 * Support Wall API
 *
 * Returns data for the support wall including:
 * - Recent contributions feed
 * - Leaderboard (ranked by total contributions)
 * - Impact stats (total raised, blocks funded, contributors)
 *
 * Query params:
 * - view: 'recent' | 'leaderboard' (default: 'recent')
 * - limit: Number of items (default: 20 for recent, 50 for leaderboard)
 */

// Tier thresholds in cents
const TIER_THRESHOLDS = {
  platinum: 1000000, // $10,000+
  gold: 250000,       // $2,500+
  silver: 50000,      // $500+
  bronze: 0,          // < $500
}

function getTier(amountCents: number): 'platinum' | 'gold' | 'silver' | 'bronze' {
  if (amountCents >= TIER_THRESHOLDS.platinum) return 'platinum'
  if (amountCents >= TIER_THRESHOLDS.gold) return 'gold'
  if (amountCents >= TIER_THRESHOLDS.silver) return 'silver'
  return 'bronze'
}

interface LeaderboardEntry {
  rank: number
  displayName: string
  isAnonymous: boolean
  totalAmount: number // In dollars
  tier: 'platinum' | 'gold' | 'silver' | 'bronze'
  contributionCount: number
  blocksSponsored: number
}

interface RecentContribution {
  id: number
  type: 'donation' | 'sponsorship'
  amount: number
  displayName: string
  isAnonymous: boolean
  message: string | null
  blockName: string | null
  createdAt: string
}

interface ImpactStats {
  totalRaised: number
  blocksFunded: number
  totalContributors: number
  recentContributors: number // Last 30 days
}

export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams
  const view = searchParams.get('view') || 'recent'
  const limit = Math.min(100, Math.max(1, parseInt(searchParams.get('limit') || (view === 'leaderboard' ? '50' : '20'))))

  try {
    // Always get impact stats
    const statsResults = await db
      .select({
        totalRaised: sum(transactions.amount),
        blocksFunded: count(sql`CASE WHEN ${transactions.blockId} IS NOT NULL THEN 1 END`),
        totalContributors: count(sql`DISTINCT ${transactions.sponsorId}`),
      })
      .from(transactions)
      .where(eq(transactions.status, 'completed'))

    const thirtyDaysAgo = new Date()
    thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30)

    const [recentStats] = await db
      .select({
        recentContributors: count(sql`DISTINCT ${transactions.sponsorId}`),
      })
      .from(transactions)
      .where(
        and(
          eq(transactions.status, 'completed'),
          sql`${transactions.createdAt} > ${thirtyDaysAgo.toISOString()}`
        )
      )

    const stats: ImpactStats = {
      totalRaised: Number(statsResults[0]?.totalRaised || 0) / 100,
      blocksFunded: Number(statsResults[0]?.blocksFunded || 0),
      totalContributors: Number(statsResults[0]?.totalContributors || 0),
      recentContributors: Number(recentStats?.recentContributors || 0),
    }

    if (view === 'leaderboard') {
      // Leaderboard: Aggregate by sponsor, rank by total
      const leaderboardResults = await db
        .select({
          sponsorId: transactions.sponsorId,
          totalAmount: sum(transactions.amount),
          contributionCount: count(transactions.id),
          blocksSponsored: count(sql`CASE WHEN ${transactions.blockId} IS NOT NULL THEN 1 END`),
          displayName: sponsors.name,
          businessName: sponsors.businessName,
          isAnonymous: sponsors.preferencesIsAnonymous,
        })
        .from(transactions)
        .leftJoin(sponsors, eq(transactions.sponsorId, sponsors.id))
        .where(eq(transactions.status, 'completed'))
        .groupBy(
          transactions.sponsorId,
          sponsors.name,
          sponsors.businessName,
          sponsors.preferencesIsAnonymous
        )
        .orderBy(desc(sum(transactions.amount)))
        .limit(limit)

      const leaderboard: LeaderboardEntry[] = leaderboardResults.map((row, index) => {
        const totalAmountCents = Number(row.totalAmount || 0)
        const isAnonymous = row.isAnonymous ?? false

        return {
          rank: index + 1,
          displayName: isAnonymous
            ? 'Anonymous Supporter'
            : row.businessName || row.displayName || 'Anonymous Supporter',
          isAnonymous,
          totalAmount: totalAmountCents / 100,
          tier: getTier(totalAmountCents),
          contributionCount: Number(row.contributionCount),
          blocksSponsored: Number(row.blocksSponsored),
        }
      })

      return NextResponse.json({
        view: 'leaderboard',
        leaderboard,
        stats,
        tiers: {
          platinum: { threshold: TIER_THRESHOLDS.platinum / 100, label: 'Platinum' },
          gold: { threshold: TIER_THRESHOLDS.gold / 100, label: 'Gold' },
          silver: { threshold: TIER_THRESHOLDS.silver / 100, label: 'Silver' },
          bronze: { threshold: TIER_THRESHOLDS.bronze / 100, label: 'Bronze' },
        },
      })
    }

    // Recent feed: Individual contributions
    const recentResults = await db
      .select({
        id: transactions.id,
        amount: transactions.amount,
        displayName: transactions.displayName,
        isAnonymous: transactions.isAnonymous,
        message: transactions.message,
        messageStatus: transactions.messageStatus,
        messageRedactedText: transactions.messageRedactedText,
        blockId: transactions.blockId,
        createdAt: transactions.createdAt,
        sponsorName: sponsors.name,
        businessName: sponsors.businessName,
        blockName: blocks.name,
      })
      .from(transactions)
      .leftJoin(sponsors, eq(transactions.sponsorId, sponsors.id))
      .leftJoin(blocks, eq(transactions.blockId, blocks.id))
      .where(eq(transactions.status, 'completed'))
      .orderBy(desc(transactions.createdAt))
      .limit(limit)

    const recent: RecentContribution[] = recentResults.map((row) => {
      const isAnonymous = row.isAnonymous ?? false
      const messageStatus = (row as { messageStatus?: string }).messageStatus || 'visible'
      const messageRedactedText = (row as { messageRedactedText?: string }).messageRedactedText

      let displayName: string
      if (isAnonymous) {
        displayName = 'Anonymous Supporter'
      } else if (row.displayName) {
        displayName = row.displayName
      } else if (row.businessName) {
        displayName = row.businessName
      } else if (row.sponsorName) {
        displayName = row.sponsorName
      } else {
        displayName = 'Anonymous Supporter'
      }

      // Handle message based on moderation status
      let message: string | null = null
      if (!isAnonymous && messageStatus !== 'hidden') {
        if (messageStatus === 'redacted' && messageRedactedText) {
          message = messageRedactedText
        } else if (messageStatus === 'visible') {
          message = row.message
        }
      }

      return {
        id: row.id,
        type: row.blockId ? 'sponsorship' : 'donation',
        amount: Number(row.amount) / 100,
        displayName,
        isAnonymous,
        message,
        blockName: row.blockName,
        createdAt: row.createdAt,
      }
    })

    return NextResponse.json({
      view: 'recent',
      recent,
      stats,
    })
  } catch (error) {
    console.error('Error fetching support wall data:', error)
    return NextResponse.json(
      { error: 'Failed to fetch support wall data' },
      { status: 500 }
    )
  }
}
