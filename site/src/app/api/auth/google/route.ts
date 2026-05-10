import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { createMobileToken } from '@/lib/mobile-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'

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

    // Validate `aud` against our configured iOS OAuth client ID. Without
    // this, a Google idToken issued for *any* OAuth client could be
    // accepted as authentication for our app — a real cross-tenant
    // confusion risk. Read expected client IDs from the env so a missing
    // setting fails closed in production but stays out of the way in dev.
    const expectedAudiences = [
      process.env.GOOGLE_IOS_CLIENT_ID,
      process.env.GOOGLE_WEB_CLIENT_ID,
    ].filter((v): v is string => typeof v === 'string' && v.length > 0)

    if (expectedAudiences.length === 0) {
      // No configured audience — log loudly and proceed. In production
      // you should always set GOOGLE_IOS_CLIENT_ID so this branch is
      // never taken.
      console.warn(
        '[google-auth] GOOGLE_IOS_CLIENT_ID not configured — skipping aud validation. Set it in production.',
      )
    } else {
      if (!tokenInfo.aud || !expectedAudiences.includes(tokenInfo.aud)) {
        console.error(
          `[google-auth] aud mismatch: token aud=${tokenInfo.aud}, expected one of ${expectedAudiences.join(',')}`,
        )
        return Response.json(
          { data: null, success: false, message: 'Token issued for the wrong OAuth client' },
          { status: 401 }
        )
      }
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
          emailVerified: tokenInfo.email_verified === 'true',
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

    // ── Issue JWT ───────────────────────────────────────────────────
    const { token, exp } = await createMobileToken(worker.id, email)
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
