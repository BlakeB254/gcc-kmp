import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { sendEmail } from '@/lib/email'
import { emailVerificationEmail } from '@/lib/email/templates/email-verification'
import crypto from 'crypto'

export const runtime = 'nodejs'

function generateVerificationCode(): string {
  return Math.floor(100000 + Math.random() * 900000).toString()
}

function generateVerificationToken(): string {
  return crypto.randomBytes(32).toString('hex')
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const { email, workerId } = body

    if (!email && !workerId) {
      return NextResponse.json(
        { error: 'Email or worker ID is required' },
        { status: 400 }
      )
    }

    // Find the worker
    let worker
    if (workerId) {
      const [found] = await db
        .select()
        .from(workers)
        .where(eq(workers.id, Number(workerId)))
        .limit(1)
      worker = found
    } else if (email) {
      const [found] = await db
        .select()
        .from(workers)
        .where(eq(workers.email, email.toLowerCase().trim()))
        .limit(1)
      worker = found
    }

    if (!worker) {
      return NextResponse.json(
        { error: 'No account found. Please check your email or sign up.' },
        { status: 404 }
      )
    }

    // Check if already verified
    if (worker.emailVerified) {
      return NextResponse.json({
        success: true,
        message: 'Your email is already verified. You can log in.',
        alreadyVerified: true,
      })
    }

    // Generate new verification token and code
    const verificationToken = generateVerificationToken()
    const verificationCode = generateVerificationCode()
    const verificationExpires = new Date(Date.now() + 24 * 60 * 60 * 1000) // 24 hours

    // Update worker with new token
    await db
      .update(workers)
      .set({
        emailVerificationToken: `${verificationToken}:${verificationCode}`,
        emailVerificationExpires: verificationExpires.toISOString(),
      })
      .where(eq(workers.id, worker.id))

    // Send verification email
    const appUrl = process.env.NEXT_PUBLIC_APP_URL || 'https://globalcommunitycleaners.org'
    const verificationUrl = `${appUrl}/worker/verify-email?token=${verificationToken}&id=${worker.id}`

    const emailContent = emailVerificationEmail({
      name: worker.name,
      verificationUrl,
      verificationCode,
    })

    const emailResult = await sendEmail({
      to: worker.email,
      subject: 'Verify your email - GCC Worker Application',
      html: emailContent.html,
      text: emailContent.text,
    })

    if (!emailResult.success) {
      console.error('[resend-verification] Email failed:', emailResult.error)
      return NextResponse.json(
        { error: 'Failed to send verification email. Please try again or contact support.' },
        { status: 500 }
      )
    }

    console.log('[resend-verification] Sent to:', worker.email, 'worker:', worker.id)

    return NextResponse.json({
      success: true,
      message: 'Verification email sent! Check your inbox.',
      workerId: worker.id,
    })
  } catch (error) {
    console.error('Resend verification error:', error)
    return NextResponse.json(
      { error: 'Something went wrong. Please try again.' },
      { status: 500 }
    )
  }
}
