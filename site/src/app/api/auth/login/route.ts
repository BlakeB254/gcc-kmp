import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import {
  verifyPassword,
  createMobileToken,
  createTwoFactorChallengeToken,
  requiresSecondFactor,
} from '@/lib/mobile-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'
import { clientIp, isRateLimited } from '@/lib/rate-limit'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    if (await isRateLimited(`login:${clientIp(request)}`)) {
      return Response.json(
        { data: null, success: false, message: 'Too many requests. Please try again later.' },
        { status: 429 }
      )
    }

    const body = await request.json()
    const { email: rawEmail, password } = body
    const email = rawEmail?.replace(/\s/g, '').toLowerCase()

    // Validate email format — reject if password got appended by AutoFill
    if (!email || !password || !email.includes('@') || email.length > 100) {
      return Response.json(
        { data: null, success: false, message: 'Invalid email or password' },
        { status: 401 }
      )
    }

    // Find worker with credentials (case-insensitive + trimmed)
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.email, email))
      .limit(1)

    if (!worker || !worker.salt || !worker.hash) {
      console.log(`Login failed: no worker found for email="${email}"`)
      return Response.json(
        { data: null, success: false, message: `Account not found for ${email}` },
        { status: 401 }
      )
    }

    // Verify password
    if (!verifyPassword(password, worker.hash, worker.salt)) {
      console.log(`Login failed: password mismatch for email="${email}", worker_id=${worker.id}`)
      return Response.json(
        { data: null, success: false, message: 'Incorrect password' },
        { status: 401 }
      )
    }

    // Password OK — if 2FA is enabled, return a challenge instead of a
    // session. The real token is only issued by /api/auth/verify-2fa after
    // a valid authenticator (or recovery) code.
    if (requiresSecondFactor(worker)) {
      const twoFactorToken = await createTwoFactorChallengeToken(worker.id, email)
      return Response.json({
        data: {
          token: null,
          worker: null,
          exp: 0,
          requiresTwoFactor: true,
          twoFactorToken,
        },
        success: true,
        message: null,
      })
    }

    // Generate JWT
    const { token, exp } = await createMobileToken(worker)

    // Map to mobile format
    const mobileWorker = mapWorkerToMobile(worker)

    return Response.json({
      data: {
        token,
        worker: mobileWorker,
        exp,
        requiresTwoFactor: false,
        twoFactorToken: null,
      },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('Login error:', error)
    return Response.json(
      { data: null, success: false, message: 'Authentication failed' },
      { status: 500 }
    )
  }
}
