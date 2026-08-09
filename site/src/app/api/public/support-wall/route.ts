import { NextRequest, NextResponse } from 'next/server'

import { PRIVATE_NO_STORE, publicCacheControl } from '@/lib/http/cache-policy'
import { loadSupportWall } from '@/lib/public-data/cached-loaders'

export const runtime = 'nodejs'

const cacheHeaders = {
  'Cache-Control': publicCacheControl({
    browserMaxAgeSeconds: 60,
    sharedMaxAgeSeconds: 1_800,
    staleWhileRevalidateSeconds: 1_800,
  }),
}

export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams
  const view = searchParams.get('view') || 'recent'
  const limit = Math.min(100, Math.max(1, parseInt(searchParams.get('limit') || (view === 'leaderboard' ? '50' : '20'))))

  try {
    return NextResponse.json(await loadSupportWall({ view, limit }), { headers: cacheHeaders })
  } catch (error) {
    console.error('Error fetching support wall data:', error)
    return NextResponse.json(
      { error: 'Failed to fetch support wall data' },
      { status: 500, headers: { 'Cache-Control': PRIVATE_NO_STORE } },
    )
  }
}
