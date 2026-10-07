import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq, sql } from 'drizzle-orm'
import { requireMobileAuth, hashPassword, createMobileToken } from '@/lib/mobile-auth'
import {
  apiEnvelope,
  apiFailure,
  checkPassword,
  getWorkerById,
  sendSecurityAlert,
  unauthorized,
} from '@/lib/account-settings'

export const runtime = 'nodejs'

/** Change password: current password required even with a valid session. */
export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) return unauthorized()

  const { currentPassword, newPassword } = await request.json()

  const worker = await getWorkerById(auth.workerId)
  if (!worker) return apiFailure('Worker not found', 404)

  const passwordError = checkPassword(worker, currentPassword)
  if (passwordError) return passwordError

  if (typeof newPassword !== 'string' || newPassword.length < 8) {
    return apiFailure('New password must be at least 8 characters', 400)
  }
  if (newPassword === currentPassword) {
    return apiFailure('New password must be different from your current password', 400)
  }

  // Bumping token_version signs out every other session (including any
  // held by whoever knew the old password). This device gets a fresh token
  // at the new version in the response, so it stays signed in.
  const { hash, salt } = hashPassword(newPassword)
  const [updated] = await db
    .update(workers)
    .set({
      hash,
      salt,
      resetPasswordToken: null,
      resetPasswordExpiration: null,
      tokenVersion: sql`${workers.tokenVersion} + 1`,
      updatedAt: new Date().toISOString(),
    })
    .where(eq(workers.id, worker.id))
    .returning({
      id: workers.id,
      email: workers.email,
      emailVerified: workers.emailVerified,
      tokenVersion: workers.tokenVersion,
    })

  await sendSecurityAlert(worker.email, worker.name, 'The password on your GCC account was just changed.')

  const fresh = updated ? await createMobileToken(updated) : null
  return Response.json(
    apiEnvelope({ ok: true, token: fresh?.token ?? null, exp: fresh?.exp ?? null }, 'Password updated'),
  )
}
