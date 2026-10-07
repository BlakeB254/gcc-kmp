/**
 * Second-factor check shared by every sign-in path that can issue a session
 * (mobile /api/auth/verify-2fa, web /api/auth/universal-login).
 *
 * A 6-digit code is checked as TOTP; anything else is tried as a recovery
 * code (XXXX-XXXX). A used recovery code is removed so it can never be
 * replayed, and the owner is emailed about it.
 */
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { verifyTotpCode, consumeRecoveryCode } from '@/lib/totp'
import { sendSecurityAlert } from '@/lib/account-settings'

export interface SecondFactorWorker {
  id: number
  email: string
  name: string
  totpSecret: string | null
  twoFactorRecoveryCodes: unknown
}

export async function verifySecondFactor(worker: SecondFactorWorker, code: string): Promise<boolean> {
  if (!worker.totpSecret || typeof code !== 'string' || !code) return false
  if (verifyTotpCode(worker.totpSecret, code)) return true

  const hashes = (worker.twoFactorRecoveryCodes as string[] | null) ?? []
  const remaining = consumeRecoveryCode(code, hashes)
  if (remaining === null) return false

  await db
    .update(workers)
    .set({ twoFactorRecoveryCodes: remaining, updatedAt: new Date().toISOString() })
    .where(eq(workers.id, worker.id))
  await sendSecurityAlert(
    worker.email,
    worker.name,
    'A recovery code was just used to sign in to your GCC account.',
    `${remaining.length} recovery code${remaining.length === 1 ? '' : 's'} remaining.`,
  )
  return true
}
