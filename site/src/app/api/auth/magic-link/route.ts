import { NextRequest, NextResponse } from 'next/server'
import { createMagicLink, sendMagicLinkEmail } from '@/lib/magic-link'

export const runtime = 'nodejs'

/**
 * Request a magic link login
 * POST /api/auth/magic-link
 * Body: { email: string, appType?: 'web' | 'mobile' }
 */
export async function POST(request: NextRequest) {
  try {
    const { email, appType } = await request.json()

    if (!email) {
      return NextResponse.json(
        { error: 'Email is required' },
        { status: 400 }
      )
    }

    // Basic email validation
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    if (!emailRegex.test(email)) {
      return NextResponse.json(
        { error: 'Please enter a valid email address' },
        { status: 400 }
      )
    }

    // Create magic link token
    const { token, isRateLimited } = createMagicLink(email)

    if (isRateLimited) {
      return NextResponse.json(
        { error: 'Too many requests. Please wait a few minutes and try again.' },
        { status: 429 }
      )
    }

    // Send email
    const { success, error } = await sendMagicLinkEmail(email, token, { appType })

    if (!success) {
      console.error('Failed to send magic link:', error)
      return NextResponse.json(
        { error: 'Failed to send email. Please try again.' },
        { status: 500 }
      )
    }

    return NextResponse.json({
      success: true,
      message: 'Check your email for a sign-in link',
    })

  } catch (error) {
    console.error('Magic link request error:', error)
    return NextResponse.json(
      { error: 'Something went wrong. Please try again.' },
      { status: 500 }
    )
  }
}
