import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { apiEnvelope, apiFailure, unauthorized } from '@/lib/account-settings'

export const runtime = 'nodejs'

/** Update low-sensitivity profile fields (name). No re-auth required. */
export async function PATCH(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) return unauthorized()

  const body = await request.json()
  const name = typeof body.name === 'string' ? body.name.trim() : ''
  if (name.length < 2 || name.length > 100) {
    return apiFailure('Name must be 2-100 characters', 400)
  }

  await db
    .update(workers)
    .set({ name, updatedAt: new Date().toISOString() })
    .where(eq(workers.id, auth.workerId))

  return Response.json(apiEnvelope({ name }))
}
