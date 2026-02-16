import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sponsors } from '@/db/schema'
import { eq, and, gt } from 'drizzle-orm'
import { hashPassword } from '@/lib/auth'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    const { token, password } = await request.json()

    if (!token || !password) {
      return NextResponse.json(
        { error: 'Token and password are required' },
        { status: 400 }
      )
    }

    // Validate password strength
    if (password.length < 8) {
      return NextResponse.json(
        { error: 'Password must be at least 8 characters long' },
        { status: 400 }
      )
    }

    // Find sponsor with valid (non-expired) reset token
    const now = new Date().toISOString()
    const [sponsor] = await db
      .select()
      .from(sponsors)
      .where(
        and(
          eq(sponsors.resetPasswordToken, token),
          gt(sponsors.resetPasswordExpiration, now)
        )
      )
      .limit(1)

    if (!sponsor) {
      return NextResponse.json(
        { error: 'Invalid or expired reset link. Please request a new password reset.' },
        { status: 400 }
      )
    }

    // Hash new password
    const { hash, salt } = hashPassword(password)

    // Update sponsor with new password and clear reset token
    await db
      .update(sponsors)
      .set({
        hash,
        salt,
        resetPasswordToken: null,
        resetPasswordExpiration: null,
        loginAttempts: '0',
        lockUntil: null,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(sponsors.id, sponsor.id))

    console.log('[ResetPassword:Sponsor] Password reset successful for sponsor:', sponsor.id)

    return NextResponse.json({
      success: true,
      message: 'Your password has been reset successfully. You can now log in with your new password.',
    })
  } catch (error) {
    console.error('Sponsor reset password error:', error)
    return NextResponse.json(
      { error: 'Failed to reset password. Please try again.' },
      { status: 500 }
    )
  }
}

// GET endpoint to verify token validity (for showing form vs error)
export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const token = searchParams.get('token')

    if (!token) {
      return NextResponse.json(
        { valid: false, error: 'No token provided' },
        { status: 400 }
      )
    }

    // Check if token exists and is not expired
    const now = new Date().toISOString()
    const [sponsor] = await db
      .select({ id: sponsors.id })
      .from(sponsors)
      .where(
        and(
          eq(sponsors.resetPasswordToken, token),
          gt(sponsors.resetPasswordExpiration, now)
        )
      )
      .limit(1)

    if (!sponsor) {
      return NextResponse.json({
        valid: false,
        error: 'Invalid or expired reset link. Please request a new password reset.',
      })
    }

    return NextResponse.json({ valid: true })
  } catch (error) {
    console.error('Sponsor verify reset token error:', error)
    return NextResponse.json(
      { valid: false, error: 'Failed to verify token' },
      { status: 500 }
    )
  }
}
