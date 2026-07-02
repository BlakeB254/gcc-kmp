/**
 * Admin Block Exclusion Zone (single) API
 * DELETE /api/admin/blocks/:blockId/exclusion-zones/:zoneId - Delete an exclusion zone
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blockExclusionZones } from '@/db/schema'
import { and, eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ blockId: string; zoneId: string }> }

export async function DELETE(request: NextRequest, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { blockId: blockIdStr, zoneId: zoneIdStr } = await context.params
    const blockId = parseInt(blockIdStr, 10)
    const zoneId = parseInt(zoneIdStr, 10)
    if (isNaN(blockId) || isNaN(zoneId)) {
      return NextResponse.json({ error: 'Invalid block or zone ID' }, { status: 400 })
    }

    const [deleted] = await db
      .delete(blockExclusionZones)
      .where(and(eq(blockExclusionZones.id, zoneId), eq(blockExclusionZones.blockId, blockId)))
      .returning({ id: blockExclusionZones.id })

    if (!deleted) {
      return NextResponse.json({ error: 'Exclusion zone not found' }, { status: 404 })
    }

    return NextResponse.json({ data: { id: deleted.id, deleted: true }, success: true })
  } catch (error) {
    console.error('DELETE /api/admin/blocks/[blockId]/exclusion-zones/[zoneId] error:', error)
    return NextResponse.json({ error: 'Failed to delete exclusion zone' }, { status: 500 })
  }
}
