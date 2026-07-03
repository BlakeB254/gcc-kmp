import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sponsors, transactions, media } from '@/db/schema'
import { eq, desc, sum, count, sql } from 'drizzle-orm'

export const runtime = 'nodejs'
export const revalidate = 60

// Tier thresholds in cents — kept in sync with /api/public/support-wall
// and src/lib/sponsor-tiers.ts
const TIER_THRESHOLDS = {
  platinum: 500000,  // $5,000+
  gold: 200000,       // $2,000+
  silver: 50000,     // $500+
  bronze: 0,         // < $500
}

function getTier(amountCents: number): 'platinum' | 'gold' | 'silver' | 'bronze' {
  if (amountCents >= TIER_THRESHOLDS.platinum) return 'platinum'
  if (amountCents >= TIER_THRESHOLDS.gold) return 'gold'
  if (amountCents >= TIER_THRESHOLDS.silver) return 'silver'
  return 'bronze'
}

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const { id } = await params
    const sponsorId = parseInt(id, 10)

    if (isNaN(sponsorId)) {
      return NextResponse.json({ error: 'Invalid sponsor ID' }, { status: 400 })
    }

    // Fetch sponsor data
    const [sponsor] = await db
      .select()
      .from(sponsors)
      .where(eq(sponsors.id, sponsorId))
      .limit(1)

    if (!sponsor) {
      return NextResponse.json({ error: 'Sponsor not found' }, { status: 404 })
    }

    // Check if profile is public
    if (sponsor.preferencesIsAnonymous || sponsor.profileIsPublic === false) {
      return NextResponse.json({ error: 'This profile is private' }, { status: 403 })
    }

    let logoUrl: string | null = null
    if (sponsor.businessLogoId) {
      const [logo] = await db
        .select({ url: media.url })
        .from(media)
        .where(eq(media.id, sponsor.businessLogoId))
        .limit(1)
      logoUrl = logo?.url || null
    }

    // Get transaction stats and display names
    const [stats] = await db
      .select({
        totalAmount: sum(transactions.amount),
        blocksSponsored: count(sql`CASE WHEN ${transactions.blockId} IS NOT NULL THEN 1 END`),
        transactionCount: count(),
        displayNames: sql<string>`ARRAY_AGG(DISTINCT ${transactions.displayName}) FILTER (WHERE ${transactions.displayName} IS NOT NULL)`,
        firstContribution: sql<string>`MIN(${transactions.createdAt})`,
        lastContribution: sql<string>`MAX(${transactions.createdAt})`,
      })
      .from(transactions)
      .where(eq(transactions.sponsorId, sponsorId))

    const totalContributed = Number(stats?.totalAmount) || 0
    const blocksSponsored = Number(stats?.blocksSponsored) || 0

    // Parse display names
    let displayNames: string[] = []
    if (stats?.displayNames) {
      if (Array.isArray(stats.displayNames)) {
        displayNames = stats.displayNames.filter(Boolean)
      } else if (typeof stats.displayNames === 'string') {
        displayNames = stats.displayNames
          .replace(/^\{|\}$/g, '')
          .split(',')
          .map(n => n.replace(/^"|"$/g, '').trim())
          .filter(Boolean)
      }
    }

    // Determine display name
    const displayName = displayNames[0] || sponsor.businessName || sponsor.name
    const akas = displayNames.slice(1)

    // Get recent contributions (without personal details)
    const recentContributions = await db
      .select({
        amount: transactions.amount,
        createdAt: transactions.createdAt,
        allocationType: transactions.allocationType,
      })
      .from(transactions)
      .where(eq(transactions.sponsorId, sponsorId))
      .orderBy(desc(transactions.createdAt))
      .limit(10)

    return NextResponse.json({
      sponsor: {
        id: sponsor.id,
        displayName,
        akas,
        type: sponsor.type,
        tier: getTier(totalContributed),
        logoUrl,
        // Profile info
        tagline: sponsor.profileTagline,
        bio: sponsor.profileBio,
        website: sponsor.businessWebsite || sponsor.profileSocialWebsite,
        socials: {
          twitter: sponsor.profileSocialTwitter,
          linkedin: sponsor.profileSocialLinkedIn,
          instagram: sponsor.profileSocialInstagram,
        },
        // Stats
        stats: {
          totalContributed, // in cents
          blocksSponsored,
          transactionCount: Number(stats?.transactionCount) || 0,
          memberSince: stats?.firstContribution || sponsor.createdAt,
          lastContribution: stats?.lastContribution,
        },
        // Recent activity (amounts only, no messages for privacy)
        recentContributions: recentContributions.map(c => ({
          amount: Number(c.amount),
          date: c.createdAt,
          type: c.allocationType,
        })),
      },
    })
  } catch (error) {
    console.error('Sponsor profile error:', error)
    return NextResponse.json({ error: 'Failed to fetch sponsor profile' }, { status: 500 })
  }
}
