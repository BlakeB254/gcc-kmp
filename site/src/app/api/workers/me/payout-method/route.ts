/**
 * GET/PUT /api/workers/me/payout-method
 *
 * How this worker wants to be paid for ad-hoc payouts:
 *  - 'stripe' (default): direct deposit / debit card via Stripe Connect
 *  - 'check': mailed paper check — to payoutMailingAddress when set, else
 *    the home address on file.
 *
 * PUT body: { method: 'stripe' | 'check', mailingAddress?: { name?, street,
 * city, state, zip } }. Switching to 'check' requires a usable address
 * (provided override, existing override, or complete home address).
 *
 * An actual change (method or mailing override) stamps
 * payout_method_changed_at — cash outs are held 24h after it — and notifies
 * the worker (lib/payout-destination.ts).
 */
import { NextRequest, NextResponse } from 'next/server'
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { verifyMobileToken } from '@/lib/mobile-auth'
import { parseMailingAddress, resolveMailingAddress } from '@/lib/payouts'
import { notifyPayoutDestinationChanged } from '@/lib/payout-destination'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

async function requireAuth(request: NextRequest) {
  const authHeader = request.headers.get('authorization')
  if (!authHeader?.startsWith('Bearer ')) return null
  return verifyMobileToken(authHeader.substring(7))
}

const WORKER_COLUMNS = {
  id: workers.id,
  name: workers.name,
  payoutMethod: workers.payoutMethod,
  payoutMailingAddress: workers.payoutMailingAddress,
  addressStreet: workers.addressStreet,
  addressCity: workers.addressCity,
  addressState: workers.addressState,
  addressZip: workers.addressZip,
}

function responseBody(worker: {
  name: string
  payoutMethod: 'stripe' | 'check' | null
  payoutMailingAddress: unknown
  addressStreet: string | null
  addressCity: string | null
  addressState: string | null
  addressZip: string | null
}) {
  return {
    method: worker.payoutMethod ?? 'stripe',
    // The address a check would actually go to today (override ?? home).
    effectiveMailingAddress: resolveMailingAddress(worker),
    // The explicit override, if any — lets the UI distinguish "using home
    // address" from "custom address".
    mailingAddressOverride: parseMailingAddress(worker.payoutMailingAddress, worker.name),
  }
}

export async function GET(request: NextRequest) {
  const auth = await requireAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, message: 'Not authenticated' }, { status: 401 })
  }

  const [worker] = await db
    .select(WORKER_COLUMNS)
    .from(workers)
    .where(eq(workers.id, auth.workerId))
    .limit(1)

  if (!worker) {
    return NextResponse.json({ data: null, message: 'Worker not found' }, { status: 404 })
  }

  return NextResponse.json({ data: responseBody(worker), success: true, message: null })
}

export async function PUT(request: NextRequest) {
  const auth = await requireAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, message: 'Not authenticated' }, { status: 401 })
  }

  let body: { method?: unknown; mailingAddress?: unknown }
  try {
    body = await request.json()
  } catch {
    return NextResponse.json(
      { data: null, success: false, message: 'Body must be valid JSON' },
      { status: 400 }
    )
  }

  const method = body.method
  if (method !== 'stripe' && method !== 'check') {
    return NextResponse.json(
      { data: null, success: false, message: "method must be 'stripe' or 'check'" },
      { status: 400 }
    )
  }

  const [worker] = await db
    .select(WORKER_COLUMNS)
    .from(workers)
    .where(eq(workers.id, auth.workerId))
    .limit(1)

  if (!worker) {
    return NextResponse.json({ data: null, message: 'Worker not found' }, { status: 404 })
  }

  // A provided mailingAddress replaces the override; absent keeps the
  // current one. It must parse completely when provided at all.
  let nextOverride = worker.payoutMailingAddress
  if (body.mailingAddress !== undefined && body.mailingAddress !== null) {
    const parsed = parseMailingAddress(body.mailingAddress, worker.name)
    if (!parsed) {
      return NextResponse.json(
        {
          data: null,
          success: false,
          message: 'mailingAddress must include street, city, state, and zip',
        },
        { status: 400 }
      )
    }
    nextOverride = parsed
  }

  if (method === 'check') {
    const effective = resolveMailingAddress({ ...worker, payoutMailingAddress: nextOverride })
    if (!effective) {
      return NextResponse.json(
        {
          data: null,
          success: false,
          message:
            'Add a mailing address to receive paper checks — no complete address is on file.',
        },
        { status: 400 }
      )
    }
  }

  const destinationChanged =
    (worker.payoutMethod ?? 'stripe') !== method ||
    JSON.stringify(worker.payoutMailingAddress ?? null) !== JSON.stringify(nextOverride ?? null)

  const now = new Date().toISOString()
  const [updated] = await db
    .update(workers)
    .set({
      payoutMethod: method,
      payoutMailingAddress: nextOverride ?? null,
      ...(destinationChanged ? { payoutMethodChangedAt: now } : {}),
      updatedAt: now,
    })
    .where(eq(workers.id, auth.workerId))
    .returning(WORKER_COLUMNS)

  if (destinationChanged) {
    await notifyPayoutDestinationChanged(auth.workerId)
  }

  return NextResponse.json({ data: responseBody(updated), success: true, message: null })
}
