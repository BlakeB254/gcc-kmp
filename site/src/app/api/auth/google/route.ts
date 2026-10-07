import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import {
  createMobileToken,
  createTwoFactorChallengeToken,
  requiresSecondFactor,
} from '@/lib/mobile-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'
import { googleAuthExpectedAudiences } from './_expected-audiences'

export const runtime = 'nodejs'

/**
 * Google Sign-In endpoint.
 *
 * The mobile app sends the Google ID token obtained after OAuth.  We verify it
 * by calling Google's tokeninfo endpoint, then extract email, name, and sub.
 *
 * If a worker with that email exists we log them in; otherwise we create a new
 * worker record with null hash/salt (OAuth user — no password).
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const { idToken } = body as { idToken?: string }

    if (!idToken) {
      return Response.json(
        { data: null, success: false, message: 'idToken is required' },
        { status: 400 }
      )
    }

    // Fail closed: without a configured OAuth client id there is nothing to
    // check `aud` against, and a Google idToken minted for ANY client would
    // be accepted as a sign-in to this app.
    const expectedAudiences = googleAuthExpectedAudiences(process.env)
    if (expectedAudiences.length === 0) {
      console.error(
        '[google-auth] GOOGLE_IOS_CLIENT_ID / GOOGLE_WEB_CLIENT_ID not configured — refusing Google sign-in.',
      )
      return Response.json(
        { data: null, success: false, message: 'Google sign-in is not configured on this server' },
        { status: 503 }
      )
    }

    // ── Verify token with Google ────────────────────────────────────
    const googleResponse = await fetch(
      `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`
    )

    if (!googleResponse.ok) {
      console.error(
        'Google tokeninfo failed:',
        googleResponse.status,
        await googleResponse.text()
      )
      return Response.json(
        { data: null, success: false, message: 'Invalid Google token' },
        { status: 401 }
      )
    }

    const tokenInfo = (await googleResponse.json()) as {
      sub?: string
      email?: string
      email_verified?: string
      name?: string
      given_name?: string
      family_name?: string
      aud?: string
    }

    const googleUserId = tokenInfo.sub
    const email = tokenInfo.email?.replace(/\s/g, '').toLowerCase()

    if (!googleUserId || !email || !email.includes('@')) {
      return Response.json(
        { data: null, success: false, message: 'Google token missing required claims' },
        { status: 400 }
      )
    }

    // Validate `aud`: a Google idToken issued for any other OAuth client
    // must not authenticate here (cross-tenant confusion).
    if (!tokenInfo.aud || !expectedAudiences.includes(tokenInfo.aud)) {
      console.error(
        `[google-auth] aud mismatch: token aud=${tokenInfo.aud}, expected one of ${expectedAudiences.join(',')}`,
      )
      return Response.json(
        { data: null, success: false, message: 'Token issued for the wrong OAuth client' },
        { status: 401 }
      )
    }

    // Only a Google-verified email may link to (or create) a worker by email.
    // tokeninfo reports the claim as the string "true"; accept the boolean too.
    const emailVerified =
      (tokenInfo.email_verified as unknown) === true || tokenInfo.email_verified === 'true'
    if (!emailVerified) {
      return Response.json(
        { data: null, success: false, message: 'Your Google account email is not verified' },
        { status: 401 }
      )
    }

    // ── Find or create worker ───────────────────────────────────────
    const [existing] = await db
      .select()
      .from(workers)
      .where(eq(workers.email, email))
      .limit(1)

    let worker: typeof existing

    if (existing) {
      worker = existing
    } else {
      // Derive name from Google profile or email prefix
      const name =
        tokenInfo.name ||
        [tokenInfo.given_name, tokenInfo.family_name].filter(Boolean).join(' ') ||
        email.split('@')[0]

      const now = new Date().toISOString()

      const [inserted] = await db
        .insert(workers)
        .values({
          name,
          email,
          phone: '',
          salt: null,
          hash: null,
          emailVerified: true, // checked above
          // Address is collected during onboarding. Do NOT default to Chicago/IL —
          // OAuth signups happen worldwide and a default would mislabel everyone.
          addressStreet: '',
          addressCity: '',
          addressState: '',
          addressZip: '',
          verificationStatus: 'pending',
          verificationGovernmentIdStatus: 'pending',
          verificationProofOfAddressStatus: 'pending',
          stripeOnboardingStatus: 'not_started',
          stripeInstantPayoutsEnabled: false,
          stripeDefaultPayoutMethod: 'standard',
          statsJobsCompleted: '0',
          statsTotalEarnings: '0',
          earlyAdopterTier: 'standard',
          referralCode: `GCC-${name.substring(0, 2).toUpperCase()}${Math.floor(1000 + Math.random() * 9000)}`,
          notificationsPushEnabled: false,
          notificationsSmsEnabled: true,
          notificationsEmailEnabled: true,
          createdAt: now,
          updatedAt: now,
        })
        .returning()

      worker = inserted
    }

    // ── 2FA: Google proves the email, not the second factor ─────────
    if (requiresSecondFactor(worker)) {
      const twoFactorToken = await createTwoFactorChallengeToken(worker.id, worker.email)
      return Response.json({
        data: { token: null, worker: null, exp: 0, requiresTwoFactor: true, twoFactorToken },
        success: true,
        message: null,
      })
    }

    // ── Issue JWT ───────────────────────────────────────────────────
    const { token, exp } = await createMobileToken(worker)
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
    console.error('Google auth error:', error)
    return Response.json(
      { data: null, success: false, message: 'Google authentication failed' },
      { status: 500 }
    )
  }
}
