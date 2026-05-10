import { NextRequest } from 'next/server'
import { createHash, timingSafeEqual } from 'crypto'
import { createRemoteJWKSet, jwtVerify, JWTPayload } from 'jose'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { createMobileToken } from '@/lib/mobile-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'

export const runtime = 'nodejs'

// ── Apple identity-token verification ─────────────────────────────────
//
// We fully verify Apple identity tokens against Apple's published JWKS
// rather than just decoding them. createRemoteJWKSet caches keys per
// process and handles rotation transparently — Apple advertises a
// rotation cadence, so we re-fetch on cache miss only.

const APPLE_JWKS = createRemoteJWKSet(
  new URL('https://appleid.apple.com/auth/keys'),
)

const APPLE_ISSUER = 'https://appleid.apple.com'
// The `aud` claim Apple signs is the OAuth client_id, which for native
// app sign-in is the iOS bundle identifier.
const EXPECTED_AUDIENCE = 'com.globalcommunitycleaners.worker'

interface AppleIdTokenPayload extends JWTPayload {
  email?: string
  email_verified?: string | boolean
  is_private_email?: string | boolean
  nonce?: string
  sub: string
}

type WorkerRow = typeof workers.$inferSelect

function sha256Hex(value: string) {
  return createHash('sha256').update(value, 'utf8').digest('hex')
}

function timingSafeStringEqual(actual: string, expected: string) {
  const actualBuffer = Buffer.from(actual)
  const expectedBuffer = Buffer.from(expected)
  return actualBuffer.length === expectedBuffer.length &&
    timingSafeEqual(actualBuffer, expectedBuffer)
}

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
    const { identityToken, fullName: rawFullName, nonce: rawNonce } = body as {
      identityToken?: string
      // KMP iOS client currently sends a flat joined string. Accept either
      // shape so a future native iOS or Android caller using the structured
      // form keeps working without a server bump.
      fullName?: string | { givenName?: string; familyName?: string }
      email?: string
      nonce?: string
    }

    let normalizedGivenName = ''
    let normalizedFamilyName = ''
    if (rawFullName) {
      if (typeof rawFullName === 'string') {
        const parts = rawFullName.trim().split(/\s+/)
        normalizedGivenName = parts[0] || ''
        normalizedFamilyName = parts.slice(1).join(' ')
      } else {
        normalizedGivenName = rawFullName.givenName || ''
        normalizedFamilyName = rawFullName.familyName || ''
      }
    }
    const fullName = normalizedGivenName || normalizedFamilyName
      ? { givenName: normalizedGivenName, familyName: normalizedFamilyName }
      : undefined

    if (!identityToken) {
      return Response.json(
        { data: null, success: false, message: 'identityToken is required' },
        { status: 400 }
      )
    }

    // ── Verify Apple identity token ─────────────────────────────────
    // Full JWKS-based signature verification + claim validation. Without
    // this an attacker could forge an arbitrary identity token and POST
    // it directly to this endpoint, bypassing the on-device flow.
    let payload: AppleIdTokenPayload
    try {
      const verified = await jwtVerify<AppleIdTokenPayload>(
        identityToken,
        APPLE_JWKS,
        {
          issuer: APPLE_ISSUER,
          audience: EXPECTED_AUDIENCE,
          // jose enforces exp by default; explicit clockTolerance keeps
          // the small device/server skew window from rejecting valid
          // tokens around the boundary.
          clockTolerance: '5s',
        },
      )
      payload = verified.payload
    } catch (e) {
      console.error('Apple identity token verification failed:', e)
      return Response.json(
        { data: null, success: false, message: 'Invalid Apple identity token' },
        { status: 401 }
      )
    }

    const appleUserId = payload.sub
    if (!appleUserId) {
      return Response.json(
        { data: null, success: false, message: 'Token missing subject claim' },
        { status: 400 }
      )
    }

    if (
      typeof rawNonce !== 'string' ||
      !/^[0-9A-Za-z._-]{16,128}$/.test(rawNonce)
    ) {
      return Response.json(
        { data: null, success: false, message: 'Valid nonce is required' },
        { status: 400 }
      )
    }

    const expectedNonce = sha256Hex(rawNonce)
    if (
      typeof payload.nonce !== 'string' ||
      !timingSafeStringEqual(payload.nonce, expectedNonce)
    ) {
      return Response.json(
        { data: null, success: false, message: 'Invalid Apple Sign-In nonce' },
        { status: 401 }
      )
    }

    const [existingByAppleId] = await db
      .select()
      .from(workers)
      .where(eq(workers.appleUserId, appleUserId))
      .limit(1)

    let worker: WorkerRow | null = null

    if (existingByAppleId) {
      worker = existingByAppleId
    } else {
      // Only trust the email inside Apple's verified identity token. The
      // ASAuthorization credential email is forwarded by the client for UX,
      // but accepting it server-side would allow a valid Apple token to be
      // paired with an arbitrary email address.
      const email = (payload.email || '')
        .replace(/\s/g, '')
        .toLowerCase()
      const emailVerified = payload.email_verified === true ||
        payload.email_verified === 'true'

      if (!email || !email.includes('@') || !emailVerified) {
        return Response.json(
          {
            data: null,
            success: false,
            message: 'Apple did not provide a verified email for this first-time sign-in',
          },
          { status: 400 }
        )
      }

      // ── Find by verified email, link Apple subject, or create worker ──
      const [existing] = await db
        .select()
        .from(workers)
        .where(eq(workers.email, email))
        .limit(1)

      const now = new Date().toISOString()

      if (existing) {
        if (existing.appleUserId && existing.appleUserId !== appleUserId) {
          return Response.json(
            { data: null, success: false, message: 'This email is already linked to a different Apple account' },
            { status: 409 }
          )
        }

        const [updated] = await db
          .update(workers)
          .set({
            appleUserId,
            emailVerified: true,
            emailVerifiedAt: existing.emailVerifiedAt || now,
            updatedAt: now,
          })
          .where(eq(workers.id, existing.id))
          .returning()

        if (!updated) {
          throw new Error('Failed to link Apple account')
        }
        worker = updated
      } else {
        // Derive name: prefer Apple-provided fullName, fall back to email prefix
        const givenName = fullName?.givenName || ''
        const familyName = fullName?.familyName || ''
        const name =
          [givenName, familyName].filter(Boolean).join(' ') ||
          email.split('@')[0]

        const [inserted] = await db
          .insert(workers)
          .values({
            name,
            email,
            phone: '',
            salt: null,
            hash: null,
            appleUserId,
            emailVerified: true, // Apple verifies email
            emailVerifiedAt: now,
            // Address is collected during onboarding. Do NOT default to Chicago/IL;
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

        if (!inserted) {
          throw new Error('Failed to create Apple worker')
        }
        worker = inserted
      }
    }

    if (!worker) {
      throw new Error('Apple authentication did not resolve a worker')
    }

    // ── Issue JWT ───────────────────────────────────────────────────
    const { token, exp } = await createMobileToken(worker.id, worker.email)
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
