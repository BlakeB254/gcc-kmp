/**
 * POST /api/observations/:id/flag-media
 *
 * Privacy flag-down for public-map litter imagery (Blake, 2026-07-22): the
 * on-device screen (no people / animals / home interiors) is the first line
 * of defense; this endpoint is the safety net for anything that slips
 * through. Any authenticated user can flag — the photo is HIDDEN
 * immediately (attributes.mediaFlagged) rather than waiting for review,
 * because the cost asymmetry is the same as on-device: a wrongly-hidden
 * thumbnail is cheap, a published bystander is not. The mediaUrl itself is
 * preserved (not deleted) so an admin can review/restore later; every
 * serving endpoint must null out mediaUrl when mediaFlagged is set.
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { worldObservations } from '@/db/schema'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { eq, sql } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

export async function POST(request: NextRequest, context: RouteContext) {
  try {
    const auth = await requireMobileAuth(request)
    if (!auth) {
      return NextResponse.json({ success: false, error: 'Not authenticated' }, { status: 401 })
    }

    const { id: idParam } = await context.params
    const id = Number(idParam)
    if (!Number.isInteger(id) || id <= 0) {
      return NextResponse.json({ success: false, error: 'Invalid observation id' }, { status: 400 })
    }

    const body = await request.json().catch(() => null)
    // Free-text capped: this lands in jsonb verbatim.
    const reason = typeof body?.reason === 'string' ? body.reason.slice(0, 200) : null

    const flag = {
      mediaFlagged: true,
      mediaFlagReason: reason,
      mediaFlaggedBy: auth.workerId,
      mediaFlaggedAt: new Date().toISOString(),
    }

    const updated = await db
      .update(worldObservations)
      .set({
        attributes: sql`COALESCE(${worldObservations.attributes}, '{}'::jsonb) || ${JSON.stringify(flag)}::jsonb`,
      })
      .where(eq(worldObservations.id, id))
      .returning({ id: worldObservations.id })

    if (updated.length === 0) {
      return NextResponse.json({ success: false, error: 'Observation not found' }, { status: 404 })
    }

    return NextResponse.json({ success: true, observationId: id })
  } catch (error) {
    console.error('POST /api/observations/[id]/flag-media error:', error)
    return NextResponse.json({ success: false, error: 'Internal error' }, { status: 500 })
  }
}
