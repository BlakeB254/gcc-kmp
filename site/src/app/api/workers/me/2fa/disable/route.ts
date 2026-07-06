import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { verifyTotpCode, consumeRecoveryCode } from '@/lib/totp'
import {
  apiEnvelope,
  apiFailure,
  checkPassword,
  getWorkerById,
  sendSecurityAlert,
  unauthorized,
} from '@/lib/account-settings'

export const runtime = 'nodejs'

/**
 * Disable 2FA. Requires BOTH the password and a valid second factor
 * (current TOTP code or an unused recovery code) — turning off protection
 * is itself a protected operation.
 */
export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) return unauthorized()

  const body = await request.json()
  const code = typeof body.code === 'string' ? body.code : ''

  const worker = await getWorkerById(auth.workerId)
  if (!worker) return apiFailure('Worker not found', 404)

  const passwordError = checkPassword(worker, body.password)
  if (passwordError) return passwordError

  if (!worker.twoFactorEnabled || !worker.totpSecret) {
    return apiFailure('Two-factor authentication is not enabled', 400)
  }

  const recoveryHashes = (worker.twoFactorRecoveryCodes as string[] | null) ?? []
  const validTotp = verifyTotpCode(worker.totpSecret, code)
  const validRecovery = !validTotp && consumeRecoveryCode(code, recoveryHashes) !== null
  if (!validTotp && !validRecovery) {
    return apiFailure('Enter a valid authenticator or recovery code', 400)
  }

  await db
    .update(workers)
    .set({
      twoFactorEnabled: false,
      totpSecret: null,
      twoFactorRecoveryCodes: null,
      twoFactorEnabledAt: null,
      updatedAt: new Date().toISOString(),
    })
    .where(eq(workers.id, worker.id))

  await sendSecurityAlert(
    worker.email,
    worker.name,
    'Two-factor authentication was just turned OFF for your GCC account.',
  )

  return Response.json(apiEnvelope({ ok: true }, 'Two-factor authentication disabled'))
}
