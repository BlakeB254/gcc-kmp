/**
 * Admin route-release batches.
 * POST /api/admin/route-releases   — create a batch (turns N routes into N open jobs)
 * GET  /api/admin/route-releases   — list batches
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { routeReleaseBatches } from '@/db/schema'
import { desc } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { createRelease, toRouteReleaseBatch } from '@/lib/route-queue'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth
  try {
    const body = await request.json().catch(() => null)
    if (!body?.name || !body?.serviceAreaId || !Array.isArray(body?.routeIds) || !body?.releaseAt) {
      return NextResponse.json(
        { error: 'name, serviceAreaId, routeIds[] and releaseAt are required', success: false },
        { status: 400 },
      )
    }
    const batch = await createRelease(body, auth.workerId)
    return NextResponse.json({ data: batch, success: true }, { status: 201 })
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Failed to create release'
    console.error('POST /api/admin/route-releases error:', error)
    const status = /required|not found|must be|At least/.test(message) ? 400 : 500
    return NextResponse.json({ error: message, success: false }, { status })
  }
}

export async function GET(request: NextRequest) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth
  try {
    const limit = parseInt(request.nextUrl.searchParams.get('limit') ?? '50', 10) || 50
    const rows = await db
      .select()
      .from(routeReleaseBatches)
      .orderBy(desc(routeReleaseBatches.createdAt))
      .limit(limit)
    return NextResponse.json({ data: rows.map(toRouteReleaseBatch), success: true })
  } catch (error) {
    console.error('GET /api/admin/route-releases error:', error)
    return NextResponse.json({ error: 'Failed to list releases', success: false }, { status: 500 })
  }
}
