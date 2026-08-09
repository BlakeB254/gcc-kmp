import assert from 'node:assert/strict'
import test from 'node:test'
import 'next/dist/server/node-environment-baseline'
import {
  CacheHandler,
  IncrementalCache,
  type CacheHandlerValue,
} from 'next/dist/server/lib/incremental-cache'
import {
  createInMemoryTaggedCache,
  createPublicDataLoaders,
  type PublicDataQueries,
} from './cached-loaders'

function createQueries(): PublicDataQueries {
  let publicStatsRuns = 0
  let supportWallRuns = 0

  return {
    async loadPublicStats() {
      publicStatsRuns += 1
      return {
        workers: { approved: publicStatsRuns, pending: 0, total: publicStatsRuns },
        sponsors: { total: 0 },
        blocks: { cleaned: 0, funded: 0, available: 10 },
        financial: { totalRaised: 0, workerPayouts: 0 },
        waitlist: { total: 0 },
        communityReports: { total: 0 },
        movement: { total: publicStatsRuns },
      }
    },
    async loadImpactStats() {
      return {
        workers: { total: 0, approved: 0, jobsCompleted: 0, totalEarnings: 0 },
        sponsors: { total: 0, totalContributed: 0, blocksFunded: 0 },
        jobs: { total: 0, completed: 0, active: 0 },
        blocks: { total: 0, cleaned: 0, funded: 0, available: 0 },
        movement: { waitlistCount: 0 },
      }
    },
    async loadSponsorWall() {
      return {
        sponsors: [],
        recent: [],
        stats: { totalBlocks: 0, totalRaised: 0, sponsorCount: 0 },
      }
    },
    async loadSupportWall(input) {
      supportWallRuns += 1
      const stats = {
        totalRaised: input.limit * 1_000 + supportWallRuns,
        blocksFunded: 0,
        totalContributors: 0,
        recentContributors: 0,
      }

      if (input.view === 'leaderboard') {
        return {
          view: 'leaderboard',
          leaderboard: [],
          stats,
          tiers: {
            platinum: { threshold: 5000, label: 'Platinum' },
            gold: { threshold: 2000, label: 'Gold' },
            silver: { threshold: 500, label: 'Silver' },
            bronze: { threshold: 0, label: 'Bronze' },
          },
        }
      }

      return { view: 'recent', recent: [], stats }
    },
  }
}

class MemoryCacheHandler extends CacheHandler {
  static entries = new Map<string, CacheHandlerValue>()

  override async get(cacheKey: string): Promise<CacheHandlerValue | null> {
    return MemoryCacheHandler.entries.get(cacheKey) ?? null
  }

  override async set(cacheKey: string, value: CacheHandlerValue['value']): Promise<void> {
    MemoryCacheHandler.entries.set(cacheKey, { lastModified: Date.now(), value })
  }
}

function createNextIncrementalCache(): IncrementalCache {
  MemoryCacheHandler.entries.clear()

  return new IncrementalCache({
    dev: false,
    requestHeaders: {},
    maxMemoryCacheSize: 1_024 * 1_024,
    getPrerenderManifest: () => ({
      version: 4,
      routes: {},
      dynamicRoutes: {},
      notFoundRoutes: [],
      preview: {
        previewModeId: 'test-preview-mode',
        previewModeEncryptionKey: 'test-preview-encryption-key',
        previewModeSigningKey: 'test-preview-signing-key',
      },
    }),
    CurCacheHandler: MemoryCacheHandler,
  })
}

test('cached public stats returns the first database value throughout its five-minute window', async () => {
  // Catches removing the cache wrapper (or giving every request a distinct key),
  // which would re-run this public database query on every request.
  let now = 1_000
  const loaders = createPublicDataLoaders(createQueries(), createInMemoryTaggedCache(() => now))

  const first = await loaders.loadPublicStats()
  now += 299_999
  const second = await loaders.loadPublicStats()
  now += 1
  const afterExpiry = await loaders.loadPublicStats()

  assert.equal(first.workers.approved, 1)
  assert.equal(second.workers.approved, 1)
  assert.equal(afterExpiry.workers.approved, 2)
})

test('cached support-wall values never cross view or limit scopes', async () => {
  // Catches a support-wall cache key that omits either query input and returns a
  // response serialized for a different public URL.
  const loaders = createPublicDataLoaders(createQueries(), createInMemoryTaggedCache(() => 1_000))

  const recentTwenty = await loaders.loadSupportWall({ view: 'recent', limit: 20 })
  const recentTwentyOne = await loaders.loadSupportWall({ view: 'recent', limit: 21 })
  const leaderboardTwenty = await loaders.loadSupportWall({ view: 'leaderboard', limit: 20 })

  assert.equal(recentTwenty.stats.totalRaised, 20_001)
  assert.equal(recentTwentyOne.stats.totalRaised, 21_002)
  assert.equal(leaderboardTwenty.stats.totalRaised, 20_003)
  assert.equal(recentTwenty.view, 'recent')
  assert.equal(leaderboardTwenty.view, 'leaderboard')
})

test('default loaders use Next incremental cache entries without crossing support-wall scopes', async () => {
  // Catches removing the production unstable_cache adapter or dropping loader
  // arguments before they reach it, which would recompute matching requests or
  // serve one URL scope's serialized value to another.
  const cacheGlobal = globalThis as typeof globalThis & { __incrementalCache?: IncrementalCache }
  const originalCache = cacheGlobal.__incrementalCache
  cacheGlobal.__incrementalCache = createNextIncrementalCache()

  try {
    const loaders = createPublicDataLoaders(createQueries())
    const recentTwenty = await loaders.loadSupportWall({ view: 'recent', limit: 20 })
    const repeatedRecentTwenty = await loaders.loadSupportWall({ view: 'recent', limit: 20 })
    const recentTwentyOne = await loaders.loadSupportWall({ view: 'recent', limit: 21 })
    const leaderboardTwenty = await loaders.loadSupportWall({ view: 'leaderboard', limit: 20 })

    assert.equal(recentTwenty.stats.totalRaised, 20_001)
    assert.equal(repeatedRecentTwenty.stats.totalRaised, 20_001)
    assert.equal(recentTwentyOne.stats.totalRaised, 21_002)
    assert.equal(leaderboardTwenty.stats.totalRaised, 20_003)
    assert.equal(leaderboardTwenty.view, 'leaderboard')
  } finally {
    if (originalCache) {
      cacheGlobal.__incrementalCache = originalCache
    } else {
      delete cacheGlobal.__incrementalCache
    }
  }
})
