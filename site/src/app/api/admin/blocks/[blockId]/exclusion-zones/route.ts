/**
 * Admin Block Exclusion Zones API
 * GET  /api/admin/blocks/:blockId/exclusion-zones - List exclusion zones for a block
 * POST /api/admin/blocks/:blockId/exclusion-zones - Create an exclusion zone
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blockExclusionZones } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ blockId: string }> }

export async function GET(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { blockId: blockIdStr } = await context.params
    const blockId = parseInt(blockIdStr, 10)
    if (isNaN(blockId)) {
      return NextResponse.json({ error: 'Invalid block ID' }, { status: 400 })
    }

    const zones = await db
      .select()
      .from(blockExclusionZones)
      .where(eq(blockExclusionZones.blockId, blockId))

    return NextResponse.json({ data: zones, success: true })
  } catch (error) {
    console.error('GET /api/admin/blocks/[blockId]/exclusion-zones error:', error)
    return NextResponse.json({ error: 'Failed to fetch exclusion zones' }, { status: 500 })
  }
}

export async function POST(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { blockId: blockIdStr } = await context.params
    const blockId = parseInt(blockIdStr, 10)
    if (isNaN(blockId)) {
      return NextResponse.json({ error: 'Invalid block ID' }, { status: 400 })
    }

    const body = await request.json().catch(() => null)
    if (!body?.polygon?.coordinates?.[0]?.length) {
      return NextResponse.json({ error: 'polygon is required' }, { status: 400 })
    }

    const [zone] = await db
      .insert(blockExclusionZones)
      .values({
        blockId,
        polygon: body.polygon,
        label: typeof body.label === 'string' ? body.label : null,
        createdByAdminId: auth.workerId,
      })
      .returning()

    return NextResponse.json({ data: zone, success: true }, { status: 201 })
  } catch (error) {
    console.error('POST /api/admin/blocks/[blockId]/exclusion-zones error:', error)
    return NextResponse.json({ error: 'Failed to create exclusion zone' }, { status: 500 })
  }
}
