import { NextResponse } from 'next/server'
import { eq, and } from 'drizzle-orm'
import { db } from '@/db'
import { sponsorDeliverableItems } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { triggerDeliverableItem } from '@/lib/sponsor-deliverable-engine'

export const runtime = 'nodejs'

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string; itemId: string }> }
) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id: idRaw, itemId: itemIdRaw } = await params
  const packageId = Number(idRaw)
  const itemId = Number(itemIdRaw)
  if (!Number.isFinite(packageId) || !Number.isFinite(itemId)) {
    return NextResponse.json({ error: 'Invalid id' }, { status: 400 })
  }

  // Verify the item belongs to this package (defense against tampering)
  const [item] = await db
    .select()
    .from(sponsorDeliverableItems)
    .where(
      and(
        eq(sponsorDeliverableItems.id, itemId),
        eq(sponsorDeliverableItems.packageId, packageId)
      )
    )
    .limit(1)
  if (!item) {
    return NextResponse.json({ error: 'Item not found in this package' }, { status: 404 })
  }

  try {
    const result = await triggerDeliverableItem({
      itemId,
      workerId: auth.workerId,
    })
    return NextResponse.json({ data: result })
  } catch (err) {
    console.error('[Deliverable trigger] failed:', err)
    return NextResponse.json(
      { error: err instanceof Error ? err.message : 'Trigger failed' },
      { status: 500 }
    )
  }
}
