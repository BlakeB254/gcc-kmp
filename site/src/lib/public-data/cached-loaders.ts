import { unstable_cache } from 'next/cache'
import { and, count, desc, eq, sql, sum } from 'drizzle-orm'

import { db } from '@/db'
import { blocks, communityReports, media, sponsors, transactions, waitlistEntries, workers } from '@/db/schema'
import { GCC_CACHE_TAGS } from './cache-tags'

type Tier = 'platinum' | 'gold' | 'silver' | 'bronze'

export type PublicStatsResponse = {
  workers: { approved: number; pending: number; total: number }
  sponsors: { total: number }
  blocks: { cleaned: number; funded: number; available: number }
  financial: { totalRaised: number; workerPayouts: number }
  waitlist: { total: number }
  communityReports: { total: number }
  movement: { total: number }
}

export type ImpactStatsResponse = {
  workers: { total: number; approved: number; jobsCompleted: number; totalEarnings: number }
  sponsors: { total: number; totalContributed: number; blocksFunded: number }
  jobs: { total: number; completed: number; active: number }
  blocks: { total: number; cleaned: number; funded: number; available: number }
  movement: { waitlistCount: number }
}

export type SponsorWallResponse = {
  sponsors: Array<{
    id: number | null
    name: string
    akas: string[]
    tier: Tier
    blocksSponsored: number
    totalContribution: number
    date: string
    isAnonymous: boolean
    type: string | null
  }>
  recent: Array<{
    id: number
    name: string
    amount: number
    message: string | null
    isAnonymous: boolean
    createdAt: string
  }>
  stats: { totalBlocks: number; totalRaised: number; sponsorCount: number }
}

type SupportWallStats = {
  totalRaised: number
  blocksFunded: number
  totalContributors: number
  recentContributors: number
}

export type SupportWallResponse =
  | {
      view: 'leaderboard'
      leaderboard: Array<{
        rank: number
        sponsorId: number | null
        displayName: string
        isAnonymous: boolean
        totalAmount: number
        tier: Tier
        contributionCount: number
        blocksSponsored: number
        logoUrl: string | null
        tagline: string | null
        bio: string | null
        website: string | null
      }>
      stats: SupportWallStats
      tiers: Record<Tier, { threshold: number; label: string }>
    }
  | {
      view: 'recent'
      recent: Array<{
        id: number
        type: 'donation' | 'sponsorship'
        amount: number
        displayName: string
        isAnonymous: boolean
        message: string | null
        blockName: string | null
        createdAt: string
      }>
      stats: SupportWallStats
    }

export type PublicDataQueries = {
  loadPublicStats: () => Promise<PublicStatsResponse>
  loadImpactStats: () => Promise<ImpactStatsResponse>
  loadSponsorWall: () => Promise<SponsorWallResponse>
  loadSupportWall: (input: { view: string; limit: number }) => Promise<SupportWallResponse>
}

type CacheConfig = {
  key: string
  tags: string[]
  revalidate: number
}

export type TaggedCache = <Args extends unknown[], Value>(
  config: CacheConfig,
  loader: (...args: Args) => Promise<Value>,
) => (...args: Args) => Promise<Value>

function nextTaggedCache<Args extends unknown[], Value>(
  config: CacheConfig,
  loader: (...args: Args) => Promise<Value>,
): (...args: Args) => Promise<Value> {
  return unstable_cache(loader, [config.key], {
    tags: config.tags,
    revalidate: config.revalidate,
  })
}

export function createInMemoryTaggedCache(now: () => number): TaggedCache {
  const entries = new Map<string, { expiresAt: number; value: unknown }>()

  return (config, loader) => async (...args) => {
    const key = JSON.stringify([config.key, args])
    const cached = entries.get(key)
    if (cached && cached.expiresAt > now()) {
      return cached.value as Awaited<ReturnType<typeof loader>>
    }

    const value = await loader(...args)
    entries.set(key, { expiresAt: now() + config.revalidate * 1_000, value })
    return value
  }
}

export function createPublicDataLoaders(
  queries: PublicDataQueries,
  cache: TaggedCache = nextTaggedCache,
): PublicDataQueries {
  return {
    loadPublicStats: cache(
      { key: GCC_CACHE_TAGS.publicStats, tags: [GCC_CACHE_TAGS.publicStats], revalidate: 300 },
      queries.loadPublicStats,
    ),
    loadImpactStats: cache(
      { key: GCC_CACHE_TAGS.impactStats, tags: [GCC_CACHE_TAGS.impactStats], revalidate: 300 },
      queries.loadImpactStats,
    ),
    loadSponsorWall: cache(
      { key: GCC_CACHE_TAGS.sponsorWall, tags: [GCC_CACHE_TAGS.sponsorWall], revalidate: 1_800 },
      queries.loadSponsorWall,
    ),
    loadSupportWall: cache(
      { key: GCC_CACHE_TAGS.supportWall, tags: [GCC_CACHE_TAGS.supportWall], revalidate: 1_800 },
      queries.loadSupportWall,
    ),
  }
}

export async function queryPublicStats(): Promise<PublicStatsResponse> {
  const [
    workersCountResult,
    sponsorsCountResult,
    blocksCleanedResult,
    blocksFundedResult,
    totalRaisedResult,
    waitlistCountResult,
    communityReportsCountResult,
  ] = await Promise.all([
    db.select({ count: sql<number>`count(*)` }).from(workers).where(eq(workers.verificationStatus, 'approved')),
    db.select({ count: sql<number>`count(*)` }).from(sponsors),
    db.select({ count: sql<number>`count(*)` }).from(blocks).where(eq(blocks.status, 'completed')),
    db.select({ count: sql<number>`count(*)` }).from(blocks).where(eq(blocks.status, 'funded')),
    db.select({ total: sql<number>`COALESCE(SUM(amount), 0)` }).from(transactions).where(eq(transactions.status, 'completed')),
    db.select({ count: sql<number>`count(*)` }).from(waitlistEntries),
    db.select({ count: sql<number>`count(*)` }).from(communityReports),
  ])

  const [pendingWorkersResult] = await db
    .select({ count: sql<number>`count(*)` })
    .from(workers)
    .where(eq(workers.verificationStatus, 'pending'))

  const approvedWorkers = Number(workersCountResult[0]?.count) || 0
  const pendingWorkers = Number(pendingWorkersResult?.count) || 0
  const sponsorCount = Number(sponsorsCountResult[0]?.count) || 0
  const waitlistCount = Number(waitlistCountResult[0]?.count) || 0

  return {
    workers: { approved: approvedWorkers, pending: pendingWorkers, total: approvedWorkers + pendingWorkers },
    sponsors: { total: sponsorCount },
    blocks: {
      cleaned: Number(blocksCleanedResult[0]?.count) || 0,
      funded: Number(blocksFundedResult[0]?.count) || 0,
      available: 10,
    },
    financial: {
      totalRaised: Number(totalRaisedResult[0]?.total) || 0,
      workerPayouts: Number(totalRaisedResult[0]?.total) || 0,
    },
    waitlist: { total: waitlistCount },
    communityReports: { total: Number(communityReportsCountResult[0]?.count) || 0 },
    movement: { total: approvedWorkers + pendingWorkers + sponsorCount + waitlistCount },
  }
}

export async function queryImpactStats(): Promise<ImpactStatsResponse> {
  const workersResult = await db.execute(sql`SELECT COUNT(*) as total_workers, COUNT(*) FILTER (WHERE verification_status = 'approved') as approved_workers, COALESCE(SUM(stats_jobs_completed::numeric), 0) as total_jobs_completed, COALESCE(SUM(stats_total_earnings::numeric), 0) as total_earnings_paid FROM workers`)
  const sponsorsResult = await db.execute(sql`SELECT COUNT(*) as total_sponsors, COALESCE(SUM(stats_total_contributed::numeric), 0) as total_contributed, COALESCE(SUM(stats_blocks_funded::numeric), 0) as total_blocks_funded FROM sponsors`)
  const jobsResult = await db.execute(sql`SELECT COUNT(*) as total_jobs, COUNT(*) FILTER (WHERE status = 'completed') as completed_jobs, COUNT(*) FILTER (WHERE status = 'in_progress') as active_jobs FROM jobs`)
  const blocksResult = await db.execute(sql`SELECT COUNT(*) as total_blocks, COUNT(*) FILTER (WHERE status = 'completed') as cleaned_blocks, COUNT(*) FILTER (WHERE status = 'funded') as funded_blocks, COUNT(*) FILTER (WHERE status = 'available') as available_blocks FROM blocks`)
  const movementResult = await db.execute(sql`SELECT COUNT(*) as waitlist_count FROM waitlist_entries`)

  const workersResultRow = workersResult.rows[0] as Record<string, unknown>
  const sponsorsResultRow = sponsorsResult.rows[0] as Record<string, unknown>
  const jobsResultRow = jobsResult.rows[0] as Record<string, unknown>
  const blocksResultRow = blocksResult.rows[0] as Record<string, unknown>
  const movementResultRow = movementResult.rows[0] as Record<string, unknown>

  return {
    workers: { total: Number(workersResultRow.total_workers) || 0, approved: Number(workersResultRow.approved_workers) || 0, jobsCompleted: Number(workersResultRow.total_jobs_completed) || 0, totalEarnings: Number(workersResultRow.total_earnings_paid) || 0 },
    sponsors: { total: Number(sponsorsResultRow.total_sponsors) || 0, totalContributed: Number(sponsorsResultRow.total_contributed) || 0, blocksFunded: Number(sponsorsResultRow.total_blocks_funded) || 0 },
    jobs: { total: Number(jobsResultRow.total_jobs) || 0, completed: Number(jobsResultRow.completed_jobs) || 0, active: Number(jobsResultRow.active_jobs) || 0 },
    blocks: { total: Number(blocksResultRow.total_blocks) || 0, cleaned: Number(blocksResultRow.cleaned_blocks) || 0, funded: Number(blocksResultRow.funded_blocks) || 0, available: Number(blocksResultRow.available_blocks) || 0 },
    movement: { waitlistCount: Number(movementResultRow.waitlist_count) || 0 },
  }
}

const SPONSOR_TIER_THRESHOLDS = { platinum: 1_000_000, gold: 250_000, silver: 50_000 }
const SUPPORT_TIER_THRESHOLDS = { platinum: 500_000, gold: 200_000, silver: 50_000, bronze: 0 }

function getTier(amountCents: number, thresholds: typeof SPONSOR_TIER_THRESHOLDS | typeof SUPPORT_TIER_THRESHOLDS): Tier {
  if (amountCents >= thresholds.platinum) return 'platinum'
  if (amountCents >= thresholds.gold) return 'gold'
  if (amountCents >= thresholds.silver) return 'silver'
  return 'bronze'
}

export async function querySponsorWall(): Promise<SponsorWallResponse> {
  const sponsorData = await db
    .select({
      sponsorId: transactions.sponsorId,
      totalAmount: sum(transactions.amount),
      blocksSponsored: count(sql`CASE WHEN ${transactions.blockId} IS NOT NULL THEN 1 END`),
      lastContribution: sql<string>`MAX(${transactions.createdAt})`,
      displayNames: sql<string>`ARRAY_AGG(DISTINCT ${transactions.displayName}) FILTER (WHERE ${transactions.displayName} IS NOT NULL)`,
      sponsorName: sponsors.name,
      businessName: sponsors.businessName,
      sponsorType: sponsors.type,
      preferencesIsAnonymous: sponsors.preferencesIsAnonymous,
    })
    .from(transactions)
    .leftJoin(sponsors, eq(transactions.sponsorId, sponsors.id))
    .where(eq(transactions.status, 'completed'))
    .groupBy(transactions.sponsorId, sponsors.name, sponsors.businessName, sponsors.type, sponsors.preferencesIsAnonymous)
    .orderBy(desc(sum(transactions.amount)))
    .limit(100)

  const sponsorList = sponsorData.map((sponsor) => {
    const totalContributed = Number(sponsor.totalAmount) || 0
    const isAnonymous = sponsor.preferencesIsAnonymous === true
    const displayNames = !sponsor.displayNames
      ? []
      : Array.isArray(sponsor.displayNames)
        ? sponsor.displayNames.filter(Boolean)
        : sponsor.displayNames.replace(/^\{|\}$/g, '').split(',').map((name) => name.replace(/^"|"$/g, '').trim()).filter(Boolean)
    const name = isAnonymous
      ? 'Anonymous Sponsor'
      : displayNames[0] || sponsor.businessName || sponsor.sponsorName || 'Anonymous Sponsor'

    return {
      id: sponsor.sponsorId,
      name,
      akas: isAnonymous ? [] : displayNames.slice(1),
      tier: getTier(totalContributed, SPONSOR_TIER_THRESHOLDS),
      blocksSponsored: Number(sponsor.blocksSponsored) || 0,
      totalContribution: totalContributed,
      date: sponsor.lastContribution,
      isAnonymous,
      type: sponsor.sponsorType,
    }
  })

  const [statsResult] = await db
    .select({
      totalRaised: sum(transactions.amount),
      totalBlocks: count(sql`CASE WHEN ${transactions.blockId} IS NOT NULL THEN 1 END`),
      sponsorCount: count(sql`DISTINCT ${transactions.sponsorId}`),
    })
    .from(transactions)
    .where(eq(transactions.status, 'completed'))

  const recentContributions = await db
    .select({ id: transactions.id, amount: transactions.amount, displayName: transactions.displayName, isAnonymous: transactions.isAnonymous, message: transactions.message, messageStatus: transactions.messageStatus, messageRedactedText: transactions.messageRedactedText, createdAt: transactions.createdAt, sponsorName: sponsors.name, businessName: sponsors.businessName })
    .from(transactions)
    .leftJoin(sponsors, eq(transactions.sponsorId, sponsors.id))
    .where(eq(transactions.status, 'completed'))
    .orderBy(desc(transactions.createdAt))
    .limit(20)

  return {
    sponsors: sponsorList,
    recent: recentContributions.map((transaction) => {
      const isAnonymous = transaction.isAnonymous ?? false
      const messageStatus = transaction.messageStatus || 'visible'
      return {
        id: transaction.id,
        name: isAnonymous ? 'Anonymous Supporter' : transaction.displayName || transaction.businessName || transaction.sponsorName || 'Anonymous Supporter',
        amount: Number(transaction.amount) || 0,
        message: !isAnonymous && messageStatus !== 'hidden' ? messageStatus === 'redacted' && transaction.messageRedactedText ? transaction.messageRedactedText : messageStatus === 'visible' ? transaction.message : null : null,
        isAnonymous,
        createdAt: transaction.createdAt,
      }
    }),
    stats: { totalBlocks: Number(statsResult?.totalBlocks) || 0, totalRaised: Number(statsResult?.totalRaised) || 0, sponsorCount: Number(statsResult?.sponsorCount) || 0 },
  }
}

export async function querySupportWall(input: { view: string; limit: number }): Promise<SupportWallResponse> {
  const statsResults = await db
    .select({ totalRaised: sum(transactions.amount), blocksFunded: count(sql`CASE WHEN ${transactions.blockId} IS NOT NULL THEN 1 END`), totalContributors: count(sql`DISTINCT ${transactions.sponsorId}`) })
    .from(transactions)
    .where(eq(transactions.status, 'completed'))
  const thirtyDaysAgo = new Date()
  thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30)
  const [recentStats] = await db
    .select({ recentContributors: count(sql`DISTINCT ${transactions.sponsorId}`) })
    .from(transactions)
    .where(and(eq(transactions.status, 'completed'), sql`${transactions.createdAt} > ${thirtyDaysAgo.toISOString()}`))
  const stats: SupportWallStats = { totalRaised: Number(statsResults[0]?.totalRaised || 0) / 100, blocksFunded: Number(statsResults[0]?.blocksFunded || 0), totalContributors: Number(statsResults[0]?.totalContributors || 0), recentContributors: Number(recentStats?.recentContributors || 0) }

  if (input.view === 'leaderboard') {
    const leaderboardResults = await db
      .select({ sponsorId: transactions.sponsorId, totalAmount: sum(transactions.amount), contributionCount: count(transactions.id), blocksSponsored: count(sql`CASE WHEN ${transactions.blockId} IS NOT NULL THEN 1 END`), displayName: sponsors.name, businessName: sponsors.businessName, isAnonymous: sponsors.preferencesIsAnonymous, profileIsPublic: sponsors.profileIsPublic, tagline: sponsors.profileTagline, bio: sponsors.profileBio, website: sponsors.businessWebsite, logoUrl: media.url })
      .from(transactions)
      .leftJoin(sponsors, eq(transactions.sponsorId, sponsors.id))
      .leftJoin(media, eq(sponsors.businessLogoId, media.id))
      .where(eq(transactions.status, 'completed'))
      .groupBy(transactions.sponsorId, sponsors.name, sponsors.businessName, sponsors.preferencesIsAnonymous, sponsors.profileIsPublic, sponsors.profileTagline, sponsors.profileBio, sponsors.businessWebsite, media.url)
      .orderBy(desc(sum(transactions.amount)))
      .limit(input.limit)

    return {
      view: 'leaderboard',
      leaderboard: leaderboardResults.map((row, index) => {
        const totalAmountCents = Number(row.totalAmount || 0)
        const isAnonymous = row.isAnonymous ?? false
        const showProfileDetails = !isAnonymous && (row.profileIsPublic ?? true)
        return { rank: index + 1, sponsorId: showProfileDetails ? row.sponsorId : null, displayName: isAnonymous ? 'Anonymous Supporter' : row.businessName || row.displayName || 'Anonymous Supporter', isAnonymous, totalAmount: totalAmountCents / 100, tier: getTier(totalAmountCents, SUPPORT_TIER_THRESHOLDS), contributionCount: Number(row.contributionCount), blocksSponsored: Number(row.blocksSponsored), logoUrl: showProfileDetails ? row.logoUrl || null : null, tagline: showProfileDetails ? row.tagline || null : null, bio: showProfileDetails ? row.bio || null : null, website: showProfileDetails ? row.website || null : null }
      }),
      stats,
      tiers: {
        platinum: { threshold: SUPPORT_TIER_THRESHOLDS.platinum / 100, label: 'Platinum' },
        gold: { threshold: SUPPORT_TIER_THRESHOLDS.gold / 100, label: 'Gold' },
        silver: { threshold: SUPPORT_TIER_THRESHOLDS.silver / 100, label: 'Silver' },
        bronze: { threshold: SUPPORT_TIER_THRESHOLDS.bronze / 100, label: 'Bronze' },
      },
    }
  }

  const recentResults = await db
    .select({ id: transactions.id, amount: transactions.amount, displayName: transactions.displayName, isAnonymous: transactions.isAnonymous, message: transactions.message, messageStatus: transactions.messageStatus, messageRedactedText: transactions.messageRedactedText, blockId: transactions.blockId, createdAt: transactions.createdAt, sponsorName: sponsors.name, businessName: sponsors.businessName, blockName: blocks.name })
    .from(transactions)
    .leftJoin(sponsors, eq(transactions.sponsorId, sponsors.id))
    .leftJoin(blocks, eq(transactions.blockId, blocks.id))
    .where(eq(transactions.status, 'completed'))
    .orderBy(desc(transactions.createdAt))
    .limit(input.limit)

  return {
    view: 'recent',
    recent: recentResults.map((row) => {
      const isAnonymous = row.isAnonymous ?? false
      const messageStatus = row.messageStatus || 'visible'
      return { id: row.id, type: row.blockId ? 'sponsorship' : 'donation', amount: Number(row.amount) / 100, displayName: isAnonymous ? 'Anonymous Supporter' : row.displayName || row.businessName || row.sponsorName || 'Anonymous Supporter', isAnonymous, message: !isAnonymous && messageStatus !== 'hidden' ? messageStatus === 'redacted' && row.messageRedactedText ? row.messageRedactedText : messageStatus === 'visible' ? row.message : null : null, blockName: row.blockName, createdAt: row.createdAt }
    }),
    stats,
  }
}

const cachedLoaders = createPublicDataLoaders({
  loadPublicStats: queryPublicStats,
  loadImpactStats: queryImpactStats,
  loadSponsorWall: querySponsorWall,
  loadSupportWall: querySupportWall,
})

export const loadPublicStats = cachedLoaders.loadPublicStats
export const loadImpactStats = cachedLoaders.loadImpactStats
export const loadSponsorWall = cachedLoaders.loadSponsorWall
export const loadSupportWall = cachedLoaders.loadSupportWall
