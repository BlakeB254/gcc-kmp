import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { verifyTotpCode, generateRecoveryCodes } from '@/lib/totp'
import {
  apiEnvelope,
  apiFailure,
  getWorkerById,
  sendSecurityAlert,
  unauthorized,
} from '@/lib/account-settings'

export const runtime = 'nodejs'

/**
 * Finish TOTP enrollment: a live code proves the authenticator actually
 * holds the secret from /2fa/setup. Returns the recovery codes in
 * plaintext — the ONLY time they are ever visible; the DB keeps hashes.
 */
export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) return unauthorized()

  const body = await request.json()
  const code = typeof body.code === 'string' ? body.code : ''

  const worker = await getWorkerById(auth.workerId)
  if (!worker) return apiFailure('Worker not found', 404)

  if (worker.twoFactorEnabled) {
    return apiFailure('Two-factor authentication is already enabled', 400)
  }
  if (!worker.totpSecret) {
    return apiFailure('Start 2FA setup first', 400)
  }
  if (!verifyTotpCode(worker.totpSecret, code)) {
    return apiFailure('That code is not valid — check your authenticator app and try again', 400)
  }

  const { plain, hashes } = generateRecoveryCodes()
  await db
    .update(workers)
    .set({
      twoFactorEnabled: true,
      twoFactorEnabledAt: new Date().toISOString(),
      twoFactorRecoveryCodes: hashes,
      updatedAt: new Date().toISOString(),
    })
    .where(eq(workers.id, worker.id))

  await sendSecurityAlert(
    worker.email,
    worker.name,
    'Two-factor authentication was just turned ON for your GCC account.',
  )

  return Response.json(apiEnvelope({ recoveryCodes: plain }))
}
