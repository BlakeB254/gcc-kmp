import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createInMemoryTaggedCache,
  createPublicDataLoaders,
  type PublicDataQueries,
} from './cached-loaders'

function createQueries(): PublicDataQueries {
  let publicStatsRuns = 0

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
      const stats = {
        totalRaised: input.limit,
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

  assert.equal(recentTwenty.stats.totalRaised, 20)
  assert.equal(recentTwentyOne.stats.totalRaised, 21)
  assert.equal(leaderboardTwenty.stats.totalRaised, 20)
  assert.equal(recentTwenty.view, 'recent')
  assert.equal(leaderboardTwenty.view, 'leaderboard')
})
