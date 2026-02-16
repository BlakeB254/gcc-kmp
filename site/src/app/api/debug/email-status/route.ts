import { NextRequest, NextResponse } from 'next/server'
import { resend, EMAIL_FROM, EMAIL_REPLY_TO } from '@/lib/email'

export const runtime = 'nodejs'

// TEMPORARY DEBUG ENDPOINT - Remove after debugging
export async function GET(request: NextRequest) {
  const hasApiKey = !!process.env.RESEND_API_KEY
  const apiKeyPreview = process.env.RESEND_API_KEY
    ? `${process.env.RESEND_API_KEY.substring(0, 8)}...`
    : 'NOT SET'

  return NextResponse.json({
    emailConfigured: hasApiKey,
    resendClientInitialized: !!resend,
    apiKeyPreview,
    emailFrom: EMAIL_FROM,
    emailReplyTo: EMAIL_REPLY_TO,
    appUrl: process.env.NEXT_PUBLIC_APP_URL || 'NOT SET',
    diagnosis: {
      canSendEmails: hasApiKey && !!resend,
      issues: [
        !hasApiKey && 'RESEND_API_KEY environment variable is not set',
        !resend && 'Resend client failed to initialize',
        !process.env.NEXT_PUBLIC_APP_URL && 'NEXT_PUBLIC_APP_URL not set - verification links may be wrong',
      ].filter(Boolean),
    },
  })
}

// Test email sending
export async function POST(request: NextRequest) {
  if (!resend) {
    return NextResponse.json(
      { error: 'Email not configured - RESEND_API_KEY is missing' },
      { status: 500 }
    )
  }

  try {
    const { testEmail } = await request.json()

    if (!testEmail) {
      return NextResponse.json(
        { error: 'testEmail is required in request body' },
        { status: 400 }
      )
    }

    const { data, error } = await resend.emails.send({
      from: EMAIL_FROM,
      to: testEmail,
      subject: 'GCC Test Email - Email Configuration Working',
      html: `
        <h1>Email Configuration Test</h1>
        <p>If you received this email, your GCC email configuration is working correctly!</p>
        <p>Sent at: ${new Date().toISOString()}</p>
      `,
      text: 'Email Configuration Test - If you received this, email is working!',
    })

    if (error) {
      console.error('[debug/email-status] Test email failed:', error)
      return NextResponse.json(
        { success: false, error: error.message, details: error },
        { status: 500 }
      )
    }

    console.log('[debug/email-status] Test email sent:', data?.id)
    return NextResponse.json({
      success: true,
      message: `Test email sent to ${testEmail}`,
      emailId: data?.id,
    })
  } catch (error) {
    console.error('[debug/email-status] Error:', error)
    return NextResponse.json(
      { error: error instanceof Error ? error.message : 'Unknown error' },
      { status: 500 }
    )
  }
}
