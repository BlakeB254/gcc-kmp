import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyToken } from '@/lib/auth'
import { verifyMobileToken } from '@/lib/mobile-auth'
import { isStripeConfigured } from '@/lib/stripe/client'
import { checkAccountStatus } from '@/lib/stripe/connect'

export const runtime = 'nodejs'

async function getAuthenticatedWorkerId(request: NextRequest): Promise<number | null> {
  const authHeader = request.headers.get('authorization')
  if (authHeader?.startsWith('Bearer ')) {
    const token = authHeader.substring(7)
    const result = await verifyMobileToken(token)
    if (result) return result.workerId
  }

  const token = request.cookies.get('worker-token')?.value
  if (!token) return null

  const payload = await verifyToken(token)
  if (!payload || payload.type !== 'worker') return null

  return payload.id
}

export async function GET(request: NextRequest) {
  try {
    const workerId = await getAuthenticatedWorkerId(request)
    if (!workerId) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }

    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, workerId))
      .limit(1)

    if (!worker) {
      return NextResponse.json({ error: 'Worker not found' }, { status: 404 })
    }

    if (!worker.stripeAccountId) {
      return NextResponse.json({
        payoutsReady: false,
        onboardingStatus: 'not_started',
        instantPayoutsEnabled: false,
      })
    }

    if (!isStripeConfigured) {
      return NextResponse.json(
        { error: 'Stripe is not configured on this server' },
        { status: 500 }
      )
    }

    const status = await checkAccountStatus(worker.stripeAccountId)

    const payoutsReady =
      status.chargesEnabled && status.payoutsEnabled && status.detailsSubmitted

    return NextResponse.json({
      payoutsReady,
      onboardingStatus: worker.stripeOnboardingStatus ?? 'not_started',
      instantPayoutsEnabled: status.instantPayoutsEnabled,
    })
  } catch (error) {
    console.error('Worker Connect status error:', error)
    return NextResponse.json({ error: 'Failed to fetch Connect status' }, { status: 500 })
  }
}
