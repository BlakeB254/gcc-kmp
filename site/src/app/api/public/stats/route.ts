import { NextResponse } from 'next/server'

import { publicCacheControl } from '@/lib/http/cache-policy'
import { loadPublicStats } from '@/lib/public-data/cached-loaders'

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
    const stats = await loadPublicStats()
    return NextResponse.json({ success: true, stats, lastUpdated: new Date().toISOString() }, { headers: cacheHeaders })
  } catch (error) {
    console.error('Public stats error:', error)
    return NextResponse.json({
      success: false,
      stats: {
        workers: { approved: 0, pending: 0, total: 0 },
        sponsors: { total: 0 },
        blocks: { cleaned: 0, funded: 0, available: 10 },
        financial: { totalRaised: 0, workerPayouts: 0 },
        waitlist: { total: 0 },
        movement: { total: 0 },
      },
      lastUpdated: new Date().toISOString(),
    }, { headers: cacheHeaders })
  }
}
