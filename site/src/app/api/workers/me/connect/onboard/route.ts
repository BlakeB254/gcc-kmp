import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyToken } from '@/lib/auth'
import { verifyMobileToken } from '@/lib/mobile-auth'
import { isStripeConfigured } from '@/lib/stripe/client'
import {
  createConnectAccount,
  createOnboardingLink,
} from '@/lib/stripe/connect'

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

export async function POST(request: NextRequest) {
  if (!isStripeConfigured) {
    return NextResponse.json(
      { error: 'Stripe is not configured on this server' },
      { status: 500 }
    )
  }

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

    let accountId = worker.stripeAccountId

    if (!accountId) {
      const account = await createConnectAccount(String(worker.id), worker.email)
      accountId = account.id
      await db
        .update(workers)
        .set({ stripeAccountId: accountId })
        .where(eq(workers.id, workerId))
    }

    const appUrl = process.env.NEXT_PUBLIC_APP_URL || 'https://gcc.app'
    const returnUrl = `${appUrl}/workers/connect/return`
    const refreshUrl = `${appUrl}/workers/connect/refresh`

    const url = await createOnboardingLink(accountId, returnUrl, refreshUrl)

    return NextResponse.json({ url })
  } catch (error) {
    console.error('Worker Connect onboard error:', error)
    return NextResponse.json({ error: 'Failed to create onboarding link' }, { status: 500 })
  }
}
