import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { hashPassword, createToken } from '@/lib/auth'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    const { workerId, password } = await request.json()

    if (!workerId || !password) {
      return NextResponse.json(
        { error: 'Worker ID and password are required' },
        { status: 400 }
      )
    }

    if (password.length < 8) {
      return NextResponse.json(
        { error: 'Password must be at least 8 characters long' },
        { status: 400 }
      )
    }

    // Find the worker
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, Number(workerId)))
      .limit(1)

    if (!worker) {
      return NextResponse.json({ error: 'Worker not found' }, { status: 404 })
    }

    // Check if email is verified
    if (!worker.emailVerified) {
      return NextResponse.json(
        { error: 'Please verify your email first' },
        { status: 400 }
      )
    }

    // Check if password is already set
    if (worker.hash && worker.salt) {
      return NextResponse.json(
        { error: 'Password already set. Please use login or forgot password.' },
        { status: 400 }
      )
    }

    // Hash the password
    const { hash, salt } = hashPassword(password)

    // Update worker with password
    await db
      .update(workers)
      .set({
        hash,
        salt,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(workers.id, Number(workerId)))

    // Create JWT token for auto-login
    const token = await createToken({
      id: worker.id,
      email: worker.email,
      type: 'worker',
    })

    // Create response with auth cookie
    const response = NextResponse.json({
      success: true,
      message: 'Password set successfully!',
      user: {
        id: worker.id,
        name: worker.name,
        email: worker.email,
        verificationStatus: worker.verificationStatus || 'pending',
      },
    })

    // Set auth cookie
    response.cookies.set('worker-token', token, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      path: '/',
      maxAge: 60 * 60 * 24 * 90, // 90 days
    })

    return response
  } catch (error) {
    console.error('Set password error:', error)
    return NextResponse.json(
      { error: 'Failed to set password. Please try again.' },
      { status: 500 }
    )
  }
}
