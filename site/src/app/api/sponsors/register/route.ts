import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sponsors, workers, waitlistEntries } from '@/db/schema'
import { eq, sql, and } from 'drizzle-orm'
import { hashPassword } from '@/lib/auth'
import { getEarlyAdopterTier, generateReferralCode } from '@/lib/movement-rankings'
import { sendEmail } from '@/lib/email'
import { sponsorEmailVerificationEmail } from '@/lib/email/templates/sponsor-email-verification'
import { appUrl } from '@/lib/env'
import crypto from 'crypto'

export const runtime = 'nodejs'

function generateVerificationCode(): string {
  return Math.floor(100000 + Math.random() * 900000).toString()
}

function generateVerificationToken(): string {
  return crypto.randomBytes(32).toString('hex')
}

export async function POST(request: NextRequest) {
  let step = 'init'
  try {
    step = 'parse-body'
    const body = await request.json()
    const { name, password, type, business, preferences } = body
    // Normalize email to lowercase
    const email = body.email?.toLowerCase?.().trim?.()

    step = 'validate-input'
    if (!name || !email || !password) {
      return NextResponse.json(
        { error: 'Name, email, and password are required' },
        { status: 400 }
      )
    }

    if (password.length < 8) {
      return NextResponse.json(
        { error: 'Password must be at least 8 characters' },
        { status: 400 }
      )
    }

    step = 'check-existing'
    // Check if email already exists in sponsors (including unverified)
    // Note: Same email CAN exist in workers table - we allow worker+sponsor dual accounts
    let existingRows: any[] = []
    try {
      // Query without email_verified first to check if sponsor exists
      // Then check email_verified separately if it exists
      const existingResult = await db.execute(sql`
        SELECT id FROM sponsors WHERE LOWER(email) = LOWER(${email}) LIMIT 1
      `)
      existingRows = existingResult.rows as any[]

      if (existingRows.length > 0) {
        // Check if email_verified column exists and what its value is
        try {
          const verifiedResult = await db.execute(sql`
            SELECT email_verified FROM sponsors WHERE id = ${existingRows[0].id}
          `)
          const isVerified = (verifiedResult.rows[0] as any)?.email_verified

          if (isVerified === false) {
            return NextResponse.json(
              { error: 'An account with this email is pending verification. Please check your email.' },
              { status: 400 }
            )
          }
        } catch {
          // email_verified column might not exist - continue with generic message
        }

        return NextResponse.json(
          { error: 'An account with this email already exists. Please log in.' },
          { status: 400 }
        )
      }
    } catch (checkError) {
      // Details stay server-side only.
      console.error('Error checking existing sponsor:', checkError)
      return NextResponse.json(
        { error: 'Database error during registration. Please try again.' },
        { status: 500 }
      )
    }

    step = 'hash-password'
    const { hash, salt } = hashPassword(password)

    step = 'gen-verification'
    const verificationToken = generateVerificationToken()
    const verificationCode = generateVerificationCode()
    const verificationExpires = new Date(Date.now() + 24 * 60 * 60 * 1000) // 24 hours

    step = 'calc-rank'
    // Calculate global rank - only count VERIFIED users
    let globalRank = 1
    try {
      const waitlistResult = await db.execute(sql`SELECT count(*)::text as count FROM waitlist_entries`)
      const workersResult = await db.execute(sql`SELECT count(*)::text as count FROM workers WHERE email_verified = true`)
      const sponsorsResult = await db.execute(sql`SELECT count(*)::text as count FROM sponsors WHERE email_verified = true`)

      globalRank =
        parseInt((waitlistResult.rows[0] as any)?.count || '0', 10) +
        parseInt((workersResult.rows[0] as any)?.count || '0', 10) +
        parseInt((sponsorsResult.rows[0] as any)?.count || '0', 10) + 1
    } catch (countError) {
      console.error('Error calculating global rank:', countError)
    }

    step = 'get-tier'
    const earlyAdopterTier = getEarlyAdopterTier(globalRank)

    step = 'gen-referral'
    let referralCode = generateReferralCode()
    let retries = 3
    while (retries > 0) {
      const existingCode = await db.execute(sql`
        SELECT id FROM sponsors WHERE referral_code = ${referralCode} LIMIT 1
      `)
      if ((existingCode.rows as any[]).length === 0) break
      referralCode = generateReferralCode()
      retries--
    }

    step = 'insert-sponsor'
    const sponsorType = type || 'individual'
    const prefFrequency = preferences?.frequency || 'one_time'
    const prefAnonymous = Boolean(preferences?.isAnonymous)
    const prefUpdates = preferences?.wantsUpdates !== false
    const bizName = business?.companyName || null
    const bizWebsite = business?.website || null
    const verificationTokenFull = `${verificationToken}:${verificationCode}`

    const insertResult = await db.execute(sql`
      INSERT INTO sponsors (
        name, email, hash, salt, type,
        business_name, business_website,
        preferences_frequency, preferences_is_anonymous, preferences_wants_updates,
        signup_rankings_global, early_adopter_tier, referral_code,
        email_verified, email_verification_token, email_verification_expires
      ) VALUES (
        ${name}, ${email}, ${hash}, ${salt}, ${sponsorType}::enum_sponsors_type,
        ${bizName}, ${bizWebsite},
        ${prefFrequency}::enum_sponsors_preferences_frequency, ${prefAnonymous}, ${prefUpdates},
        ${String(globalRank)}, ${earlyAdopterTier}::enum_workers_early_adopter_tier, ${referralCode},
        false, ${verificationTokenFull}, ${verificationExpires.toISOString()}
      )
      RETURNING id, name, email
    `)

    step = 'verify-insert'
    const rows = insertResult.rows as any[]
    if (!rows || rows.length === 0) {
      throw new Error('Failed to create sponsor account')
    }

    const sponsor = rows[0]

    step = 'send-email'
    // Send verification email
    const verificationUrl = `${appUrl()}/sponsor/verify-email?token=${verificationToken}&id=${sponsor.id}`

    const emailContent = sponsorEmailVerificationEmail({
      name,
      verificationUrl,
      verificationCode,
    })

    await sendEmail({
      to: email,
      subject: 'Verify Your Email - GCC Sponsor Account',
      html: emailContent.html,
      text: emailContent.text,
    })

    step = 'build-response'
    // DON'T set auth cookie - they need to verify first
    return NextResponse.json({
      success: true,
      message: 'Account created! Please check your email to verify your account.',
      requiresVerification: true,
      sponsorId: sponsor.id,
    })
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Unknown error'
    const errorCode = (error as any)?.code || 'unknown'
    const errorDetail = (error as any)?.detail || (error as any)?.hint || ''

    // Full diagnostics stay server-side only; clients get a generic message.
    console.error(
      `Sponsor registration error at step [${step}]: ${errorMessage} | code: ${errorCode} | ${errorDetail}`,
      error
    )

    if (errorMessage.includes('unique') || errorMessage.includes('duplicate') || errorCode === '23505') {
      return NextResponse.json(
        { error: 'An account with this email already exists. Please log in instead.' },
        { status: 400 }
      )
    }

    return NextResponse.json(
      { error: 'Registration failed. Please try again later.' },
      { status: 500 }
    )
  }
}
