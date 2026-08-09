import { NextResponse } from 'next/server'

import { PRIVATE_NO_STORE, publicCacheControl } from '@/lib/http/cache-policy'
import { loadImpactStats } from '@/lib/public-data/cached-loaders'

export const runtime = 'nodejs'

const cacheHeaders = {
  'Cache-Control': publicCacheControl({
    browserMaxAgeSeconds: 60,
    sharedMaxAgeSeconds: 300,
    staleWhileRevalidateSeconds: 300,
  }),
}

export async function GET() {
  try {
    return NextResponse.json(await loadImpactStats(), { headers: cacheHeaders })
  } catch (error) {
    console.error('Impact stats error:', error)
    return NextResponse.json({
      workers: { total: 0, approved: 0, jobsCompleted: 0, totalEarnings: 0 },
      sponsors: { total: 0, totalContributed: 0, blocksFunded: 0 },
      jobs: { total: 0, completed: 0, active: 0 },
      blocks: { total: 0, cleaned: 0, funded: 0, available: 0 },
      movement: { waitlistCount: 0 },
    }, { headers: { 'Cache-Control': PRIVATE_NO_STORE } })
  }
}
