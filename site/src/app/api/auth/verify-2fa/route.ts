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
import { verifySecondFactor } from '@/lib/two-factor'
import { mapWorkerToMobile } from '@/lib/worker-mapper'
import { clientIp, isRateLimited } from '@/lib/rate-limit'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    if (await isRateLimited(`verify-2fa:${clientIp(request)}`)) {
      return Response.json(
        { data: null, success: false, message: 'Too many requests. Please try again later.' },
        { status: 429 },
      )
    }

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

    const verified = await verifySecondFactor(worker, code)

    if (!verified) {
      return Response.json(
        { data: null, success: false, message: 'Invalid verification code' },
        { status: 400 },
      )
    }

    const { token, exp } = await createMobileToken(worker)
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
