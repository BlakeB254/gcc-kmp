import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { hashPassword, createMobileToken } from '@/lib/mobile-auth'
import { mapWorkerToMobile } from '@/lib/worker-mapper'
import { createMagicLink, sendMagicLinkEmail } from '@/lib/magic-link'
import { clientIp, isRateLimited } from '@/lib/rate-limit'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    if (await isRateLimited(`register:${clientIp(request)}`)) {
      return Response.json(
        { data: null, success: false, message: 'Too many requests. Please try again later.' },
        { status: 429 }
      )
    }

    const body = await request.json()
    const { name, email: rawEmail, phone, password, addressStreet, addressCity, addressState, addressZip } = body
    const email = rawEmail?.trim().toLowerCase()

    if (!name || !email || !password) {
      return Response.json({ data: null, success: false, message: 'Name, email, and password are required' }, { status: 400 })
    }

    // Check existing
    const [existing] = await db
      .select()
      .from(workers)
      .where(eq(workers.email, email))
      .limit(1)

    // If user exists with a password already, reject
    if (existing && existing.hash) {
      return Response.json({ data: null, success: false, message: 'Email already registered' }, { status: 409 })
    }

    // If user exists without a password (magic-link / phone-created account),
    // NEVER set a password or issue a session from public register — anyone
    // who knows the email could take the account over (audit F-01). Require
    // email-control proof instead: send a magic link; the owner signs in with
    // it and can then set a password from their account
    // (see /api/workers/set-password).
    if (existing && !existing.hash) {
      const { token: magicLinkToken, isRateLimited: magicLinkLimited } = createMagicLink(email)
      if (!magicLinkLimited) {
        const sent = await sendMagicLinkEmail(email, magicLinkToken)
        if (!sent.success) {
          console.error('Register: magic link send failed:', sent.error)
        }
      }
      return Response.json({
        data: null,
        success: false,
        message: 'This email is already registered. We sent a sign-in link to that address — use it to sign in, then set a password from your account.',
      }, { status: 409 })
    }

    // Hash password
    const { hash, salt } = hashPassword(password)
    const now = new Date().toISOString()

    // Insert worker
    const [inserted] = await db
      .insert(workers)
      .values({
        name,
        email,
        phone: phone || '',
        salt,
        hash,
        emailVerified: false,
        // Address is collected during onboarding. Don't fall back to Chicago/IL —
        // global signups must not be silently re-located.
        addressStreet: addressStreet || '',
        addressCity: addressCity || '',
        addressState: addressState || '',
        addressZip: addressZip || '',
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

    // Generate JWT. The new row is unverified, so this is always a 'worker'
    // session — an ADMIN_EMAILS address gets admin only after verifying.
    const { token, exp } = await createMobileToken(inserted)

    // Map to mobile format
    const worker = mapWorkerToMobile(inserted)

    return Response.json({
      data: { token, worker, exp, requiresTwoFactor: false, twoFactorToken: null },
      success: true,
      message: null,
    }, { status: 201 })
  } catch (error) {
    console.error('Register error:', error)
    return Response.json(
      { data: null, success: false, message: 'Registration failed' },
      { status: 500 }
    )
  }
}
