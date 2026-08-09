/**
 * GET /api/articles
 *
 * Returns the list of published articles (summaries — no body) so the
 * iOS app can render the Articles tab without paying for the full body
 * up front. The detail body is fetched separately from
 * /api/articles/[slug].
 */
import { NextResponse } from 'next/server'
import { getArticles } from './_articles'
import { publicCacheControl } from '@/lib/http/cache-policy'

export const revalidate = 3_600

const cacheHeaders = {
  'Cache-Control': publicCacheControl({
    browserMaxAgeSeconds: 60,
    sharedMaxAgeSeconds: 3_600,
    staleWhileRevalidateSeconds: 3_600,
  }),
}

export async function GET() {
  return NextResponse.json(
    { data: getArticles(), success: true, message: null },
    { headers: cacheHeaders },
  )
}
