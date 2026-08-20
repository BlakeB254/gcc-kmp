/**
 * POST /api/auth/universal-login
 *
 * Single sign-in for the whole site. A user may have BOTH a worker row and
 * a sponsor row (same email = same person). The flow:
 *
 *   1. Look up the email in BOTH tables.
 *   2. Try the password against whichever rows have a hash. Whichever side
 *      verifies first decides the "primary" account type for landing-page
 *      redirect.
 *   3. Once one side verifies, issue cookies + tokens for ALL roles the
 *      email matches — so a worker who's also a sponsor doesn't have to
 *      re-log-in to flip dashboards.
 *
 * Response includes:
 *   - accountType: the side that verified the password ("worker" | "sponsor")
 *   - roles: every role this email has access to ("worker" | "sponsor" | "admin")
 *   - user: a profile from the verified side
 *   - token: a Bearer JWT (used by the admin panel's localStorage flow)
 *
 * The mobile app keeps using /api/auth/login (Bearer-token-based) — that
 * endpoint is untouched. The legacy /api/workers/login and
 * /api/sponsors/login web cookie endpoints have been removed; their pages
 * now redirect to /login and use this universal endpoint.
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq, sql } from 'drizzle-orm'
import { verifyPassword, createToken } from '@/lib/auth'
import { createMobileToken } from '@/lib/mobile-auth'
import { clientIp, isRateLimited } from '@/lib/rate-limit'

export const runtime = 'nodejs'

const ADMIN_EMAILS = (process.env.ADMIN_EMAILS || '')
  .split(',')
  .map((e) => e.trim().toLowerCase())
  .filter(Boolean)

function isAdminEmail(email: string): boolean {
  return ADMIN_EMAILS.includes(email.toLowerCase())
}

function cookieOpts() {
  return {
    httpOnly: true,
    secure: process.env.NODE_ENV === 'production',
    sameSite: 'lax' as const,
    path: '/',
    maxAge: 60 * 60 * 24 * 90,
  }
}

export async function POST(request: NextRequest) {
  try {
    if (await isRateLimited(`universal-login:${clientIp(request)}`)) {
      return NextResponse.json(
        { success: false, error: 'Too many requests. Please try again later.' },
        { status: 429 },
      )
    }

    const body = await request.json()
    const password: string | undefined = body?.password
    const email: string | undefined = body?.email?.replace?.(/\s/g, '').toLowerCase()

    if (!email || !password || !email.includes('@') || email.length > 100) {
      return NextResponse.json(
        { success: false, error: 'Email and password are required' },
        { status: 400 },
      )
    }

    // Look up both sides up-front. Either may be null.
    const [worker] = await db.select().from(workers).where(eq(workers.email, email)).limit(1)

    const sponsorResult = await db.execute(sql`
      SELECT id, name, email, type, hash, salt, email_verified
      FROM sponsors WHERE email = ${email} LIMIT 1
    `)
    const sponsor = (sponsorResult.rows as Array<Record<string, unknown>>)[0] as
      | { id: number; name: string; email: string; type: string; hash: string | null; salt: string | null; email_verified: boolean }
      | undefined

    const workerPasswordOk =
      !!worker?.hash && !!worker?.salt && verifyPassword(password, worker.hash, worker.salt)
    const sponsorPasswordOk =
      !!sponsor?.hash && !!sponsor?.salt && verifyPassword(password, sponsor.hash, sponsor.salt)

    // If neither matched: figure out which error to surface.
    if (!workerPasswordOk && !sponsorPasswordOk) {
      const accountExists = !!worker || !!sponsor
      return NextResponse.json(
        {
          success: false,
          error: accountExists ? 'Incorrect password' : 'No account found with this email',
        },
        { status: 401 },
      )
    }

    // Pre-flight blockers (email-verify, rejected). Apply to whichever side
    // verified the password.
    if (workerPasswordOk) {
      if (!worker!.emailVerified) {
        return NextResponse.json(
          { success: false, error: 'Please verify your email before signing in.', code: 'EMAIL_NOT_VERIFIED', workerId: worker!.id },
          { status: 403 },
        )
      }
      if (worker!.verificationStatus === 'rejected') {
        return NextResponse.json(
          {
            success: false,
            error: 'Your application was not approved.',
            code: 'VERIFICATION_REJECTED',
            reason: worker!.verificationReviewNotes || 'Please contact support.',
          },
          { status: 403 },
        )
      }
    } else if (sponsorPasswordOk && !sponsor!.email_verified) {
      return NextResponse.json(
        { success: false, error: 'Please verify your email before signing in.', code: 'EMAIL_NOT_VERIFIED', sponsorId: sponsor!.id },
        { status: 403 },
      )
    }

    // ── Build the response. We set BOTH cookies if both rows exist —
    //    one verified password proves the user owns this email, so they
    //    get session access to every role tied to it.
    const roles: Array<'worker' | 'sponsor' | 'admin'> = []
    if (worker) roles.push('worker')
    if (sponsor) roles.push('sponsor')
    if (isAdminEmail(email)) roles.push('admin')

    const accountType: 'worker' | 'sponsor' = workerPasswordOk ? 'worker' : 'sponsor'

    // Bearer token for the admin panel + any client preferring localStorage.
    // Workers + sponsors share the same JWT shape via createMobileToken — the
    // role claim differentiates. We mint it from whichever id we know.
    const idForBearer = (worker?.id ?? sponsor?.id) as number
    const { token: bearerToken } = await createMobileToken(idForBearer, email)

    const userPayload =
      accountType === 'worker'
        ? {
            id: worker!.id,
            name: worker!.name,
            email: worker!.email,
            verificationStatus: worker!.verificationStatus || 'pending',
            documents: {
              governmentIdUploaded: worker!.verificationGovernmentIdId !== null,
              proofOfAddressUploaded: worker!.verificationProofOfAddressId !== null,
            },
          }
        : {
            id: sponsor!.id,
            name: sponsor!.name,
            email: sponsor!.email,
            type: sponsor!.type,
          }

    const response = NextResponse.json({
      success: true,
      accountType,
      roles,
      token: bearerToken,
      user: userPayload,
    })

    // Issue worker-token cookie if a worker row exists.
    if (worker) {
      const t = await createToken({ id: worker.id, email, type: 'worker' })
      response.cookies.set('worker-token', t, cookieOpts())
    }
    // Issue sponsor-token cookie if a sponsor row exists.
    if (sponsor) {
      const t = await createToken({ id: sponsor.id, email, type: 'sponsor' })
      response.cookies.set('sponsor-token', t, cookieOpts())
    }

    return response
  } catch (error) {
    console.error('Universal login error:', error)
    return NextResponse.json(
      { success: false, error: 'Sign-in failed. Please try again.' },
      { status: 500 },
    )
  }
}
