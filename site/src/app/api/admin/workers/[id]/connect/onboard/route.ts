import { NextResponse } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { isStripeConfigured } from '@/lib/stripe/client'
import {
  createConnectAccount,
  createOnboardingLink,
} from '@/lib/stripe/connect'
import { appUrl as resolveAppUrl } from '@/lib/env'
import { payoutsPausedResponse } from '@/lib/payouts-enabled'

export const runtime = 'nodejs'

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  // PAYOUTS_ENABLED master switch (lib/payouts-enabled.ts): 503 before anything else.
  const paused = payoutsPausedResponse()
  if (paused) return paused

  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  if (!isStripeConfigured) {
    return NextResponse.json(
      { error: 'Stripe is not configured on this server' },
      { status: 500 }
    )
  }

  const { id } = await params
  const workerId = parseInt(id, 10)
  if (isNaN(workerId)) {
    return NextResponse.json({ error: 'Invalid worker ID' }, { status: 400 })
  }

  try {
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, workerId))
      .limit(1)

    if (!worker) {
      return NextResponse.json({ error: 'Worker not found' }, { status: 404 })
    }

    if (worker.verificationStatus !== 'approved') {
      return NextResponse.json(
        { error: 'Worker must be approved before Connect onboarding' },
        { status: 422 }
      )
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

    const appUrl = resolveAppUrl()
    const returnUrl = `${appUrl}/workers/connect/return`
    const refreshUrl = `${appUrl}/workers/connect/refresh`

    const url = await createOnboardingLink(accountId, returnUrl, refreshUrl)

    return NextResponse.json({ url })
  } catch (error) {
    console.error('Admin worker Connect onboard error:', error)
    return NextResponse.json({ error: 'Failed to create onboarding link' }, { status: 500 })
  }
}
