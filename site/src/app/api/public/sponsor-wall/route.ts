import { NextResponse } from 'next/server'

import { PRIVATE_NO_STORE, publicCacheControl } from '@/lib/http/cache-policy'
import { loadSponsorWall } from '@/lib/public-data/cached-loaders'

export const runtime = 'nodejs'

const cacheHeaders = {
  'Cache-Control': publicCacheControl({
    browserMaxAgeSeconds: 60,
    sharedMaxAgeSeconds: 1_800,
    staleWhileRevalidateSeconds: 1_800,
  }),
}

export async function GET() {
  try {
    return NextResponse.json(await loadSponsorWall(), { headers: cacheHeaders })
  } catch (error) {
    console.error('Sponsor wall error:', error)
    return NextResponse.json({
      sponsors: [],
      stats: { totalBlocks: 0, totalRaised: 0, sponsorCount: 0 },
    }, { headers: { 'Cache-Control': PRIVATE_NO_STORE } })
  }
}
