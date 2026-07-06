/**
 * 2FA Verification
 * POST /api/auth/verify-2fa — second step of login for workers with
 * two-factor enabled.
 *
 * Login returns a short-lived challenge JWT (`twoFactorToken`) instead of a
 * session when 2FA is on. This endpoint validates that challenge, then
 * accepts either a live TOTP code from the worker's authenticator app or an
 * unused recovery code (which is consumed), and only then issues the real
 * session token. Stateless by design — Vercel functions share no memory.
 */
import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { createMobileToken, verifyTwoFactorChallengeToken } from '@/lib/mobile-auth'
import { verifyTotpCode, consumeRecoveryCode } from '@/lib/totp'
import { mapWorkerToMobile } from '@/lib/worker-mapper'
import { sendSecurityAlert } from '@/lib/account-settings'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    const { twoFactorToken, code } = await request.json()

    if (!twoFactorToken || typeof code !== 'string' || !code) {
      return Response.json(
        { data: null, success: false, message: 'Missing twoFactorToken or code' },
        { status: 400 },
      )
    }

    const challenge = await verifyTwoFactorChallengeToken(twoFactorToken)
    if (!challenge) {
      return Response.json(
        { data: null, success: false, message: 'Your sign-in expired — please log in again' },
        { status: 401 },
      )
    }

    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, challenge.workerId))
      .limit(1)

    if (!worker || !worker.twoFactorEnabled || !worker.totpSecret) {
      return Response.json(
        { data: null, success: false, message: 'Two-factor authentication is not enabled' },
        { status: 400 },
      )
    }

    // A 6-digit code is checked as TOTP; anything else is tried as a
    // recovery code (XXXX-XXXX). A used recovery code is removed so it
    // can never be replayed.
    let verified = verifyTotpCode(worker.totpSecret, code)
    if (!verified) {
      const hashes = (worker.twoFactorRecoveryCodes as string[] | null) ?? []
      const remaining = consumeRecoveryCode(code, hashes)
      if (remaining !== null) {
        verified = true
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
      }
    }

    if (!verified) {
      return Response.json(
        { data: null, success: false, message: 'Invalid verification code' },
        { status: 400 },
      )
    }

    const { token, exp } = await createMobileToken(worker.id, challenge.email)
    const mobileWorker = mapWorkerToMobile(worker as unknown as Record<string, unknown>)

    return Response.json({
      data: { token, worker: mobileWorker, exp, requiresTwoFactor: false, twoFactorToken: null },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('Verify 2FA error:', error)
    return Response.json(
      { data: null, success: false, message: '2FA verification failed' },
      { status: 500 },
    )
  }
}
