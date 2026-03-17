import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const runtime = 'nodejs'

export async function PATCH(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return Response.json({ error: 'Unauthorized' }, { status: 401 })
  }

  const { handle } = await request.json()

  // Validate handle format: 3-20 chars, alphanumeric + underscores only
  const cleaned = (handle ?? '').replace(/^@/, '').toLowerCase()
  if (!/^[a-z0-9_]{3,20}$/.test(cleaned)) {
    return Response.json(
      { error: 'Handle must be 3-20 characters, letters/numbers/underscores only' },
      { status: 400 }
    )
  }

  try {
    await db
      .update(workers)
      .set({ displayHandle: cleaned, updatedAt: new Date().toISOString() })
      .where(eq(workers.id, auth.workerId))

    return Response.json({ data: { displayHandle: cleaned } })
  } catch (e: any) {
    if (e.code === '23505') { // unique violation
      return Response.json({ error: 'Handle already taken' }, { status: 409 })
    }
    throw e
  }
}
