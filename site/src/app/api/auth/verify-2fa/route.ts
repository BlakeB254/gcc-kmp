/**
 * 2FA Verification
 * POST /api/auth/verify-2fa — Verify a 2FA code and return JWT
 *
 * The twoFactorToken is a signed JWT containing the challenge data
 * (worker ID, email, expected code, expiry). This avoids server-side
 * state since Vercel functions are stateless.
 */
import { NextRequest } from 'next/server'
import { jwtVerify, SignJWT } from 'jose'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { createMobileToken } from '@/lib/mobile-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'

export const runtime = 'nodejs'

const TWO_FA_SECRET = new TextEncoder().encode(
  process.env.JWT_SECRET || 'development-secret-change-in-production'
)

/** Create a 2FA challenge token (called from login when 2FA is enabled) */
export async function createTwoFactorToken(
  workerId: number,
  email: string,
  code: string,
): Promise<string> {
  return new SignJWT({ worker_id: workerId, email, code, purpose: '2fa' })
    .setProtectedHeader({ alg: 'HS256' })
    .setExpirationTime('5m')
    .sign(TWO_FA_SECRET)
}

export async function POST(request: NextRequest) {
  try {
    const { twoFactorToken, code } = await request.json()

    if (!twoFactorToken || !code) {
      return Response.json(
        { data: null, message: 'Missing twoFactorToken or code' },
        { status: 400 },
      )
    }

    // Verify the challenge token
    let payload: { worker_id: number; email: string; code: string }
    try {
      const result = await jwtVerify(twoFactorToken, TWO_FA_SECRET)
      payload = result.payload as typeof payload
    } catch {
      return Response.json(
        { data: null, message: 'Invalid or expired verification token' },
        { status: 401 },
      )
    }

    // Verify the code matches
    if (payload.code !== code) {
      return Response.json(
        { data: null, message: 'Invalid verification code' },
        { status: 401 },
      )
    }

    // Look up worker
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, payload.worker_id))
      .limit(1)

    if (!worker) {
      return Response.json(
        { data: null, message: 'Worker not found' },
        { status: 404 },
      )
    }

    // Issue JWT
    const { token, exp } = await createMobileToken(worker.id, payload.email)
    const mobileWorker = mapWorkerToMobile(worker)

    return Response.json({
      data: { token, worker: mobileWorker, exp, requiresTwoFactor: false, twoFactorToken: null },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('Verify 2FA error:', error)
    return Response.json(
      { data: null, message: '2FA verification failed' },
      { status: 500 },
    )
  }
}
