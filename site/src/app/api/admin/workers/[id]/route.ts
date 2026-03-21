import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'

export const runtime = 'nodejs'

export async function GET(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await params
  const workerId = parseInt(id, 10)
  if (isNaN(workerId)) {
    return NextResponse.json({ error: 'Invalid worker ID' }, { status: 400 })
  }

  try {
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, workerId))
      .limit(1)

    if (!worker) {
      return NextResponse.json({ error: 'Worker not found' }, { status: 404 })
    }

    return NextResponse.json({ data: mapWorkerToMobile(worker as Record<string, unknown>) })
  } catch (error) {
    console.error('Error fetching worker:', error)
    return NextResponse.json({ error: 'Failed to fetch worker' }, { status: 500 })
  }
}

export async function PUT(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await params
  const workerId = parseInt(id, 10)
  if (isNaN(workerId)) {
    return NextResponse.json({ error: 'Invalid worker ID' }, { status: 400 })
  }

  try {
    const body = await request.json()

    const updateData: Record<string, unknown> = {}
    if (body.name !== undefined) updateData.name = body.name
    if (body.phone !== undefined) updateData.phone = body.phone
    if (body.addressStreet !== undefined) updateData.addressStreet = body.addressStreet
    if (body.addressCity !== undefined) updateData.addressCity = body.addressCity
    if (body.addressState !== undefined) updateData.addressState = body.addressState
    if (body.addressZip !== undefined) updateData.addressZip = body.addressZip
    if (body.earlyAdopterTier !== undefined) updateData.earlyAdopterTier = body.earlyAdopterTier

    if (Object.keys(updateData).length === 0) {
      return NextResponse.json({ error: 'No fields to update' }, { status: 400 })
    }

    const [updated] = await db
      .update(workers)
      .set(updateData)
      .where(eq(workers.id, workerId))
      .returning()

    if (!updated) {
      return NextResponse.json({ error: 'Worker not found' }, { status: 404 })
    }

    return NextResponse.json({ data: mapWorkerToMobile(updated as Record<string, unknown>) })
  } catch (error) {
    console.error('Error updating worker:', error)
    return NextResponse.json({ error: 'Failed to update worker' }, { status: 500 })
  }
}

export async function DELETE(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await params
  const workerId = parseInt(id, 10)
  if (isNaN(workerId)) {
    return NextResponse.json({ error: 'Invalid worker ID' }, { status: 400 })
  }

  try {
    await db.delete(workers).where(eq(workers.id, workerId))
    return NextResponse.json({ data: { deleted: true } })
  } catch (error) {
    console.error('Error deleting worker:', error)
    return NextResponse.json({ error: 'Failed to delete worker' }, { status: 500 })
  }
}
