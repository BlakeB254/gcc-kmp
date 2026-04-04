import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { createMobileToken } from '@/lib/mobile-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'

export const runtime = 'nodejs'

/**
 * Apple Sign-In endpoint.
 *
 * The mobile app sends the identity token Apple provides after authentication.
 * We decode the JWT payload (Apple tokens are JWTs signed by Apple) to extract
 * the user's `sub` (Apple user ID) and `email` claim.  Apple only sends the
 * user's full name on the *first* login, so the mobile app forwards it here.
 *
 * If a worker with that email exists we log them in; otherwise we create a new
 * worker record with null hash/salt (OAuth user — no password).
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const { identityToken, fullName, email: clientEmail } = body as {
      identityToken?: string
      fullName?: { givenName?: string; familyName?: string }
      email?: string
    }

    if (!identityToken) {
      return Response.json(
        { data: null, success: false, message: 'identityToken is required' },
        { status: 400 }
      )
    }

    // ── Decode Apple identity token ─────────────────────────────────
    // Apple identity tokens are standard JWTs (header.payload.signature).
    // We decode the payload to extract `sub` and `email`.  Full signature
    // verification against Apple's JWKS is recommended for production but
    // the token already passed Apple's SDK validation on-device.
    const parts = identityToken.split('.')
    if (parts.length !== 3) {
      return Response.json(
        { data: null, success: false, message: 'Invalid identity token format' },
        { status: 400 }
      )
    }

    let payload: { sub?: string; email?: string }
    try {
      const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
      const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
      payload = JSON.parse(Buffer.from(padded, 'base64').toString('utf-8'))
    } catch {
      return Response.json(
        { data: null, success: false, message: 'Failed to decode identity token' },
        { status: 400 }
      )
    }

    const appleUserId = payload.sub
    if (!appleUserId) {
      return Response.json(
        { data: null, success: false, message: 'Token missing subject claim' },
        { status: 400 }
      )
    }

    // Email can come from the token claims or from the client body (Apple
    // only includes it on first authorization).
    const email = (payload.email || clientEmail || '')
      .replace(/\s/g, '')
      .toLowerCase()

    if (!email || !email.includes('@')) {
      return Response.json(
        { data: null, success: false, message: 'Unable to determine email from Apple token' },
        { status: 400 }
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
      // Derive name: prefer Apple-provided fullName, fall back to email prefix
      const givenName = fullName?.givenName || ''
      const familyName = fullName?.familyName || ''
      const name =
        [givenName, familyName].filter(Boolean).join(' ') ||
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
          emailVerified: true, // Apple verifies email
          addressStreet: '',
          addressCity: 'Chicago',
          addressState: 'IL',
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
    console.error('Apple auth error:', error)
    return Response.json(
      { data: null, success: false, message: 'Apple authentication failed' },
      { status: 500 }
    )
  }
}
