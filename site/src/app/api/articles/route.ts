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

export const dynamic = 'force-dynamic'

export async function GET() {
  return NextResponse.json({
    data: getArticles(),
    success: true,
    message: null,
  })
}
