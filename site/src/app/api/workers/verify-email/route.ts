import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    const { token, code, workerId } = await request.json()

    if (!workerId) {
      return NextResponse.json({ error: 'Invalid verification link. Please use the link from your email or enter your 6-digit code.' }, { status: 400 })
    }

    if (!token && !code) {
      return NextResponse.json({ error: 'Please enter the 6-digit verification code from your email.' }, { status: 400 })
    }

    // Find the worker
    const [worker] = await db.select().from(workers).where(eq(workers.id, Number(workerId))).limit(1)

    if (!worker) {
      return NextResponse.json({ error: 'We couldn\'t find your account. Please check your verification link or sign up again.' }, { status: 404 })
    }

    // Check if already verified
    if (worker.emailVerified) {
      return NextResponse.json({
        success: true,
        message: 'Email already verified',
        alreadyVerified: true,
      })
    }

    // Check if verification token has expired
    if (worker.emailVerificationExpires) {
      const expiresAt = new Date(worker.emailVerificationExpires)
      if (expiresAt < new Date()) {
        return NextResponse.json(
          { error: 'Verification link has expired. Please request a new one.' },
          { status: 400 }
        )
      }
    }

    // Get stored token and code
    const storedTokenAndCode = worker.emailVerificationToken || ''
    const [storedToken, storedCode] = storedTokenAndCode.split(':')

    // Verify token or code
    const isValidToken = token && storedToken === token
    const isValidCode = code && storedCode === code

    if (!isValidToken && !isValidCode) {
      return NextResponse.json({ error: 'The verification code you entered is incorrect. Please check your email and try again.' }, { status: 400 })
    }

    // Mark email as verified
    await db
      .update(workers)
      .set({
        emailVerified: true,
        emailVerifiedAt: new Date().toISOString(),
        emailVerificationToken: null,
        emailVerificationExpires: null,
      })
      .where(eq(workers.id, Number(workerId)))

    return NextResponse.json({
      success: true,
      message: 'Email verified successfully! You can now log in.',
    })
  } catch (error) {
    console.error('Email verification error:', error)
    return NextResponse.json({ error: 'Verification failed. Please try again.' }, { status: 500 })
  }
}

// Also support GET for link clicks
export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams
  const token = searchParams.get('token')
  const workerId = searchParams.get('id')

  if (!token || !workerId) {
    return NextResponse.redirect(new URL('/worker/verify-email?error=invalid_link', request.url))
  }

  try {
    // Find the worker
    const [worker] = await db.select().from(workers).where(eq(workers.id, Number(workerId))).limit(1)

    if (!worker) {
      return NextResponse.redirect(new URL('/worker/verify-email?error=not_found', request.url))
    }

    // Check if already verified
    if (worker.emailVerified) {
      return NextResponse.redirect(new URL(`/worker/verify-email?success=already_verified&id=${workerId}`, request.url))
    }

    // Check if verification token has expired
    if (worker.emailVerificationExpires) {
      const expiresAt = new Date(worker.emailVerificationExpires)
      if (expiresAt < new Date()) {
        return NextResponse.redirect(new URL('/worker/verify-email?error=expired', request.url))
      }
    }

    // Get stored token
    const storedTokenAndCode = worker.emailVerificationToken || ''
    const [storedToken] = storedTokenAndCode.split(':')

    if (storedToken !== token) {
      return NextResponse.redirect(new URL('/worker/verify-email?error=invalid_token', request.url))
    }

    // Mark email as verified
    await db
      .update(workers)
      .set({
        emailVerified: true,
        emailVerifiedAt: new Date().toISOString(),
        emailVerificationToken: null,
        emailVerificationExpires: null,
      })
      .where(eq(workers.id, Number(workerId)))

    return NextResponse.redirect(new URL(`/worker/verify-email?success=verified&id=${workerId}`, request.url))
  } catch (error) {
    console.error('Email verification error:', error)
    return NextResponse.redirect(new URL('/worker/verify-email?error=failed', request.url))
  }
}
