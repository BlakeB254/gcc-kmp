import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { generateTotpSecret, buildOtpauthUri } from '@/lib/totp'
import {
  apiEnvelope,
  apiFailure,
  checkPassword,
  getWorkerById,
  unauthorized,
} from '@/lib/account-settings'

export const runtime = 'nodejs'

/**
 * Begin TOTP enrollment: re-auth with password, mint a secret, hand it to
 * the app. 2FA is NOT armed yet — the secret only becomes active once
 * /2fa/enable verifies a live code from the authenticator (standard
 * enrollment pattern, prevents locking yourself out with a mistyped setup).
 * Calling setup again before enabling simply rotates the pending secret.
 */
export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) return unauthorized()

  const body = await request.json()

  const worker = await getWorkerById(auth.workerId)
  if (!worker) return apiFailure('Worker not found', 404)

  const passwordError = checkPassword(worker, body.password)
  if (passwordError) return passwordError

  if (worker.twoFactorEnabled) {
    return apiFailure('Two-factor authentication is already enabled', 400)
  }

  const secret = generateTotpSecret()
  await db
    .update(workers)
    .set({ totpSecret: secret, updatedAt: new Date().toISOString() })
    .where(eq(workers.id, worker.id))

  return Response.json(
    apiEnvelope({ secret, otpauthUri: buildOtpauthUri(secret, worker.email) }),
  )
}
