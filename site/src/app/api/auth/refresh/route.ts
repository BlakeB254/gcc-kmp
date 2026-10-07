import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyMobileToken, createMobileToken } from '@/lib/mobile-auth'

export const runtime = 'nodejs'

export async function POST(request: NextRequest) {
  try {
    const authHeader = request.headers.get('authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return NextResponse.json(
        { data: null, success: false, message: 'Missing or invalid Authorization header' },
        { status: 401 }
      )
    }

    const token = authHeader.substring(7)
    const auth = await verifyMobileToken(token)

    if (!auth) {
      return NextResponse.json(
        { data: null, success: false, message: 'Invalid or expired token' },
        { status: 401 }
      )
    }

    // Look up worker email for role determination in the new token
    const [worker] = await db
      .select({
        id: workers.id,
        email: workers.email,
        emailVerified: workers.emailVerified,
        tokenVersion: workers.tokenVersion,
      })
      .from(workers)
      .where(eq(workers.id, auth.workerId))
      .limit(1)

    if (!worker) {
      return NextResponse.json(
        { data: null, success: false, message: 'Worker not found' },
        { status: 401 }
      )
    }

    // Issue a fresh token. verifyMobileToken only accepts session tokens
    // (never a 2FA challenge) at the worker's current token_version, so this
    // cannot upgrade a challenge or resurrect a revoked session.
    const { token: newToken, exp } = await createMobileToken(worker)

    return NextResponse.json({
      data: { token: newToken, exp },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('Token refresh error:', error)
    return NextResponse.json(
      { data: null, success: false, message: 'Token refresh failed' },
      { status: 500 }
    )
  }
}
