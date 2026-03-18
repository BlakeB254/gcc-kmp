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
