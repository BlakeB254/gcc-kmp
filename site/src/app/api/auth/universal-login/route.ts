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
 *   3. If the WORKER side verified and the worker has 2FA on, require a
 *      TOTP/recovery code (`twoFactorCode`) before issuing anything — the
 *      response is `code: 'TWO_FACTOR_REQUIRED'` until one is supplied.
 *   4. Issue a session only for the side whose own password (and 2FA)
 *      verified. A worker who is also a sponsor signs in to each side with
 *      that side's password; one password never opens the other account.
 *
 * Response includes:
 *   - accountType: the side that verified the password ("worker" | "sponsor")
 *   - roles: every role this email has access to ("worker" | "sponsor" | "admin")
 *   - user: a profile from the verified side
 *   - token: a worker Bearer JWT (the admin panel's localStorage flow) —
 *     ONLY when the worker password (and 2FA) verified; null otherwise. A
 *     sponsor-only login never receives a worker token.
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
import { createMobileToken, requiresSecondFactor, roleFor } from '@/lib/mobile-auth'
import { verifySecondFactor } from '@/lib/two-factor'
import { clientIp, isRateLimited } from '@/lib/rate-limit'

export const runtime = 'nodejs'

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
    const twoFactorCode: string =
      typeof body?.twoFactorCode === 'string' ? body.twoFactorCode.trim() : ''

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

    // Pre-flight blockers (email-verify, rejected, 2FA). Apply to whichever
    // side verified the password.
    let workerAuthenticated = false
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
      if (requiresSecondFactor(worker!)) {
        if (!twoFactorCode) {
          return NextResponse.json(
            {
              success: false,
              error: 'Enter the 6-digit code from your authenticator app (or a recovery code).',
              code: 'TWO_FACTOR_REQUIRED',
            },
            { status: 401 },
          )
        }
        if (!(await verifySecondFactor(worker!, twoFactorCode))) {
          return NextResponse.json(
            { success: false, error: 'Invalid verification code', code: 'TWO_FACTOR_INVALID' },
            { status: 401 },
          )
        }
      }
      workerAuthenticated = true
    } else if (sponsorPasswordOk && !sponsor!.email_verified) {
      return NextResponse.json(
        { success: false, error: 'Please verify your email before signing in.', code: 'EMAIL_NOT_VERIFIED', sponsorId: sponsor!.id },
        { status: 403 },
      )
    }

    // A session is issued only for the side whose own password (and, for a
    // worker, second factor) verified. One account's password never opens the
    // other account, even when both rows share an email.
    const issueWorker = workerAuthenticated
    const issueSponsor = sponsorPasswordOk
    const accountType: 'worker' | 'sponsor' = workerAuthenticated ? 'worker' : 'sponsor'

    // Bearer token for the admin panel + any client preferring localStorage.
    // It is a WORKER session (worker_id claim), so it is minted only from the
    // worker row whose password just verified — never from a sponsor id,
    // which would have named whatever worker shares that numeric id.
    const bearer = workerAuthenticated ? await createMobileToken(worker!) : null

    const roles: Array<'worker' | 'sponsor' | 'admin'> = []
    if (issueWorker) roles.push('worker')
    if (issueSponsor) roles.push('sponsor')
    if (workerAuthenticated && roleFor(worker!) === 'admin') roles.push('admin')

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
      token: bearer?.token ?? null,
      user: userPayload,
    })

    if (issueWorker) {
      const t = await createToken({ id: worker!.id, email, type: 'worker' })
      response.cookies.set('worker-token', t, cookieOpts())
    }
    if (issueSponsor) {
      const t = await createToken({ id: sponsor!.id, email, type: 'sponsor' })
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
