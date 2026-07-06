import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import {
  apiEnvelope,
  apiFailure,
  getWorkerById,
  sendSecurityAlert,
  unauthorized,
} from '@/lib/account-settings'

export const runtime = 'nodejs'

const MAX_ATTEMPTS = 5

/** Step 2 of changing the account email: verify the code sent to the new address. */
export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) return unauthorized()

  const body = await request.json()
  const code = typeof body.code === 'string' ? body.code.replace(/\s/g, '') : ''

  const worker = await getWorkerById(auth.workerId)
  if (!worker) return apiFailure('Worker not found', 404)

  if (!worker.pendingEmail || !worker.pendingEmailToken) {
    return apiFailure('No email change is in progress', 400)
  }
  if (!worker.pendingEmailExpires || new Date(worker.pendingEmailExpires).getTime() < Date.now()) {
    return apiFailure('That code has expired — request the change again', 400)
  }
  if ((worker.pendingEmailAttempts ?? 0) >= MAX_ATTEMPTS) {
    return apiFailure('Too many incorrect attempts — request the change again', 429)
  }

  const expectedCode = worker.pendingEmailToken.split(':')[1]
  if (!/^\d{6}$/.test(code) || code !== expectedCode) {
    await db
      .update(workers)
      .set({ pendingEmailAttempts: (worker.pendingEmailAttempts ?? 0) + 1 })
      .where(eq(workers.id, worker.id))
    return apiFailure('Incorrect code', 400)
  }

  const previousEmail = worker.email
  const newEmail = worker.pendingEmail

  try {
    await db
      .update(workers)
      .set({
        email: newEmail,
        emailVerified: true,
        emailVerifiedAt: new Date().toISOString(),
        pendingEmail: null,
        pendingEmailToken: null,
        pendingEmailExpires: null,
        pendingEmailAttempts: 0,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(workers.id, worker.id))
  } catch (e) {
    if ((e as { code?: string }).code === '23505') {
      // Another account claimed this email between request and confirm.
      return apiFailure('That email is already in use by another account', 409)
    }
    throw e
  }

  await sendSecurityAlert(
    previousEmail,
    worker.name,
    'The email address on your GCC account was just changed.',
    `New address: ${newEmail}`,
  )

  return Response.json(apiEnvelope({ email: newEmail }))
}
