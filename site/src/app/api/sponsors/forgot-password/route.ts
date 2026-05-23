import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sponsors } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { generateSessionToken } from '@/lib/auth'
import { sendEmail } from '@/lib/email'
import { passwordResetEmail } from '@/lib/email/templates/password-reset'
import { appUrl } from '@/lib/env'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    const { email } = await request.json()

    if (!email) {
      return NextResponse.json(
        { error: 'Email is required' },
        { status: 400 }
      )
    }

    // Find sponsor by email
    const [sponsor] = await db
      .select()
      .from(sponsors)
      .where(eq(sponsors.email, email.toLowerCase().trim()))
      .limit(1)

    // Always return success to prevent email enumeration attacks
    if (!sponsor) {
      console.log('[ForgotPassword:Sponsor] Email not found:', email)
      return NextResponse.json({
        success: true,
        message: 'If an account exists with this email, a password reset link has been sent.',
      })
    }

    // Generate reset token (1 hour expiry)
    const resetToken = generateSessionToken()
    const resetExpiration = new Date(Date.now() + 60 * 60 * 1000).toISOString() // 1 hour

    // Save token to database
    await db
      .update(sponsors)
      .set({
        resetPasswordToken: resetToken,
        resetPasswordExpiration: resetExpiration,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(sponsors.id, sponsor.id))

    // Build reset URL
    const baseUrl = appUrl()
    const resetUrl = `${baseUrl}/sponsor/reset-password?token=${resetToken}`

    // Send email
    const emailContent = passwordResetEmail({
      name: sponsor.name,
      resetUrl,
    })

    const emailResult = await sendEmail({
      to: sponsor.email,
      subject: 'Reset Your Password - GCC',
      html: emailContent.html,
      text: emailContent.text,
    })

    if (!emailResult.success) {
      console.error('[ForgotPassword:Sponsor] Email send failed:', emailResult.error)
    } else {
      console.log('[ForgotPassword:Sponsor] Reset email sent to:', sponsor.email)
    }

    return NextResponse.json({
      success: true,
      message: 'If an account exists with this email, a password reset link has been sent.',
    })
  } catch (error) {
    console.error('Sponsor forgot password error:', error)
    // Return success to prevent enumeration
    return NextResponse.json({
      success: true,
      message: 'If an account exists with this email, a password reset link has been sent.',
    })
  }
}
