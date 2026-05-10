/**
 * GET /api/articles/[slug]
 *
 * Returns a single article including the full markdown body so the iOS
 * app's reader view can render it. The shape mirrors the shared Kotlin
 * `Article` model (composeApp consumes this via `GccApiClient.getArticle`).
 */
import { NextRequest, NextResponse } from 'next/server'
import { getArticle } from '../_articles'

export const dynamic = 'force-dynamic'

export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ slug: string }> },
) {
  const { slug } = await params
  const article = getArticle(slug)

  if (!article) {
    return NextResponse.json(
      { data: null, success: false, message: 'Article not found' },
      { status: 404 },
    )
  }

  return NextResponse.json({
    data: article,
    success: true,
    message: null,
  })
}
