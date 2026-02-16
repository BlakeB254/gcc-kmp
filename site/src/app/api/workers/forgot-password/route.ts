import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { generateSessionToken } from '@/lib/auth'
import { sendEmail } from '@/lib/email'
import { passwordResetEmail } from '@/lib/email/templates/password-reset'

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

    // Find worker by email
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.email, email.toLowerCase().trim()))
      .limit(1)

    // Always return success to prevent email enumeration attacks
    if (!worker) {
      console.log('[ForgotPassword] Email not found:', email)
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
      .update(workers)
      .set({
        resetPasswordToken: resetToken,
        resetPasswordExpiration: resetExpiration,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(workers.id, worker.id))

    // Build reset URL
    const baseUrl = process.env.NEXT_PUBLIC_APP_URL || 'http://localhost:7300'
    const resetUrl = `${baseUrl}/worker/reset-password?token=${resetToken}`

    // Send email
    const emailContent = passwordResetEmail({
      name: worker.name,
      resetUrl,
    })

    const emailResult = await sendEmail({
      to: worker.email,
      subject: 'Reset Your Password - GCC',
      html: emailContent.html,
      text: emailContent.text,
    })

    if (!emailResult.success) {
      console.error('[ForgotPassword] Email send failed:', emailResult.error)
    } else {
      console.log('[ForgotPassword] Reset email sent to:', worker.email)
    }

    return NextResponse.json({
      success: true,
      message: 'If an account exists with this email, a password reset link has been sent.',
    })
  } catch (error) {
    console.error('Forgot password error:', error)
    // Return success to prevent enumeration
    return NextResponse.json({
      success: true,
      message: 'If an account exists with this email, a password reset link has been sent.',
    })
  }
}
