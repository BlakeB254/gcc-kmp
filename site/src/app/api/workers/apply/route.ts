import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers, waitlistEntries, sponsors } from '@/db/schema'
import { eq, sql } from 'drizzle-orm'
import { sendEmail } from '@/lib/email'
import { emailVerificationEmail } from '@/lib/email/templates/email-verification'
import { getEarlyAdopterTier, generateReferralCode } from '@/lib/movement-rankings'
import crypto from 'crypto'

export const runtime = 'nodejs'
export const maxDuration = 30

function generateVerificationCode(): string {
  return Math.floor(100000 + Math.random() * 900000).toString()
}

function generateVerificationToken(): string {
  return crypto.randomBytes(32).toString('hex')
}

interface RequestBody {
  name: string
  email: string
  phone: string
  password?: string
}

export async function POST(request: NextRequest) {
  console.log('[apply] Starting worker registration')
  try {
    let body: RequestBody
    try {
      body = await request.json()
      console.log('[apply] Parsed body:', { name: body.name, email: body.email, phone: body.phone })
    } catch {
      return Response.json(
        { message: 'We couldn\'t process your request. Please refresh the page and try again.' },
        { status: 400 },
      )
    }

    const { name, email, phone } = body

    if (!name || !email || !phone) {
      return Response.json(
        { message: 'Please fill in all required fields: name, email, and phone.' },
        { status: 400 },
      )
    }

    // Check if email already exists
    const existing = await db
      .select({ id: workers.id })
      .from(workers)
      .where(eq(workers.email, email.trim().toLowerCase()))
      .limit(1)

    if (existing.length > 0) {
      return Response.json(
        { message: 'An account with this email already exists. Please log in instead, or use a different email address.' },
        { status: 400 },
      )
    }

    // Generate verification token and code
    const verificationToken = generateVerificationToken()
    const verificationCode = generateVerificationCode()
    const verificationExpires = new Date(Date.now() + 24 * 60 * 60 * 1000)

    // Calculate movement rankings
    const signupTimestamp = new Date()

    const [{ waitlistCount }] = await db
      .select({ waitlistCount: sql<number>`count(*)` })
      .from(waitlistEntries)

    const [{ workersCount }] = await db
      .select({ workersCount: sql<number>`count(*)` })
      .from(workers)

    const [{ sponsorsCount }] = await db
      .select({ sponsorsCount: sql<number>`count(*)` })
      .from(sponsors)

    const globalRank = Number(waitlistCount) + Number(workersCount) + Number(sponsorsCount) + 1
    const earlyAdopterTier = getEarlyAdopterTier(globalRank)
    const referralCode = generateReferralCode()

    // Create worker record — address fields default to empty string via schema
    const [worker] = await db
      .insert(workers)
      .values({
        name: name.trim(),
        email: email.trim().toLowerCase(),
        phone,
        addressStreet: '',
        addressCity: '',
        addressState: '',
        addressZip: '',
        emailVerified: false,
        emailVerificationToken: `${verificationToken}:${verificationCode}`,
        emailVerificationExpires: verificationExpires.toISOString(),
        verificationStatus: 'pending',
        signupTimestamp: signupTimestamp.toISOString(),
        signupRankingsGlobal: String(globalRank),
        signupRankingsCountry: String(globalRank),
        signupRankingsState: '0',
        signupRankingsCity: '0',
        signupRankingsNeighborhood: '0',
        earlyAdopterTier,
        referralCode,
        notificationsSmsEnabled: true,
        notificationsEmailEnabled: true,
      })
      .returning({ id: workers.id })

    // Send verification email
    const appUrl = process.env.NEXT_PUBLIC_APP_URL || 'https://globalcommunitycleaners.org'
    const verificationUrl = `${appUrl}/worker/verify-email?token=${verificationToken}&id=${worker.id}`

    const emailContent = emailVerificationEmail({
      name: name.trim(),
      verificationUrl,
      verificationCode,
    })

    const emailResult = await sendEmail({
      to: email.trim().toLowerCase(),
      subject: 'Verify your email - GCC Worker Application',
      html: emailContent.html,
      text: emailContent.text,
    })

    if (!emailResult.success) {
      console.error('[apply] Email failed to send:', emailResult.error)
    } else {
      console.log('[apply] Verification email sent to:', email)
    }

    return Response.json({
      success: true,
      workerId: worker.id,
      message: emailResult.success
        ? 'Registration submitted. Please check your email to verify your account.'
        : 'Registration submitted. If you don\'t receive a verification email, please use the resend option on the verification page.',
      requiresEmailVerification: true,
      emailSent: emailResult.success,
      rankings: { global: globalRank },
      earlyAdopterTier,
      referralCode,
    })
  } catch (error) {
    console.error('Worker registration error:', error)
    const errorMessage = error instanceof Error ? error.message : 'Unknown error'

    let userMessage = 'Something went wrong during registration. Please try again, or contact support if the problem continues.'

    if (errorMessage.includes('duplicate key') || errorMessage.includes('unique constraint')) {
      userMessage = 'An account with this email already exists. Please try logging in instead.'
    } else if (errorMessage.includes('connection') || errorMessage.includes('ECONNREFUSED')) {
      userMessage = 'Unable to connect to our servers. Please check your internet connection and try again.'
    } else if (errorMessage.includes('timeout')) {
      userMessage = 'The request took too long. Please try again.'
    }

    return Response.json(
      { message: userMessage, error: errorMessage, success: false },
      { status: 500, headers: { 'Content-Type': 'application/json' } },
    )
  }
}
