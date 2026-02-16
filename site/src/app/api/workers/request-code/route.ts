import { NextRequest, NextResponse } from 'next/server'
import { generateVerificationCode } from '@/lib/phone-verification'
import { sendVerificationCode } from '@/lib/sms-gateway'

export const runtime = 'nodejs'

/**
 * Request a phone verification code
 * POST /api/workers/request-code
 * Body: { phone: string, carrier?: string }
 */
export async function POST(request: NextRequest) {
  try {
    const { phone } = await request.json()

    if (!phone) {
      return NextResponse.json(
        { error: 'Phone number is required' },
        { status: 400 }
      )
    }

    // Validate phone number format (US numbers)
    const digits = phone.replace(/\D/g, '')
    if (digits.length < 10 || digits.length > 11) {
      return NextResponse.json(
        { error: 'Please enter a valid US phone number' },
        { status: 400 }
      )
    }

    // Generate code
    const { code, isRateLimited } = generateVerificationCode(phone)

    if (isRateLimited) {
      return NextResponse.json(
        { error: 'Too many code requests. Please wait 10 minutes and try again.' },
        { status: 429 }
      )
    }

    // Send SMS via email-to-SMS gateway
    const smsResult = await sendVerificationCode(phone, code)

    if (!smsResult.success) {
      console.error('SMS gateway failed:', smsResult.error)
      // Still return success - code is generated, user can use dev code or retry
      // In production with Twilio, you'd return an error here
    }

    return NextResponse.json({
      success: true,
      message: smsResult.success
        ? 'Verification code sent to your phone'
        : 'Code generated. SMS delivery may be delayed.',
      smsSent: smsResult.success,
      carrier: smsResult.carrier,
      // Include code in development for easy testing
      ...(process.env.NODE_ENV !== 'production' && {
        devCode: code,
        devHint: 'In development, you can also use code: 123456'
      }),
    })
  } catch (error) {
    console.error('Request code error:', error)
    return NextResponse.json(
      { error: 'Failed to send verification code. Please try again.' },
      { status: 500 }
    )
  }
}
