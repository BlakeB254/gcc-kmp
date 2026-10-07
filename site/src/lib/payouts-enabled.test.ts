/**
 * PAYOUTS_ENABLED master switch: every route that can move money or credit a
 * wallet answers 503 { error: "Payouts are paused" } before touching auth, the
 * database or Stripe, unless the switch is explicitly on.
 */
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'

process.env.JWT_SECRET ??= 'test-secret-for-payout-switch-tests-0123456789'
process.env.DATABASE_URL ??= 'postgres://test:test@localhost/test'

import { __setDbForTests } from '@/db'
import { createFakeDb } from '@/test-support/fake-db'
import { payoutsEnabled, payoutsPausedResponse, PAYOUTS_PAUSED_ERROR } from './payouts-enabled'
import { POST as withdraw } from '@/app/api/workers/me/withdraw/route'
import { POST as workerConnectOnboard } from '@/app/api/workers/me/connect/onboard/route'
import { POST as adminWorkerPayout } from '@/app/api/admin/workers/[id]/payout/route'
import { POST as adminWorkerConnectOnboard } from '@/app/api/admin/workers/[id]/connect/onboard/route'
import { POST as adminJobApprove } from '@/app/api/admin/jobs/[id]/approve/route'
import { POST as adminJobReassign } from '@/app/api/admin/jobs/[id]/reassign/route'
import { POST as adminMarkMailed } from '@/app/api/admin/payouts/[id]/mark-mailed/route'

type Handler = (req: never, ctx: never) => Promise<Response>

const params = { params: Promise.resolve({ id: '1' }) }
const MONEY_ROUTES: Array<[string, Handler]> = [
  ['POST /api/workers/me/withdraw', withdraw as unknown as Handler],
  ['POST /api/workers/me/connect/onboard', workerConnectOnboard as unknown as Handler],
  ['POST /api/admin/workers/[id]/payout', adminWorkerPayout as unknown as Handler],
  ['POST /api/admin/workers/[id]/connect/onboard', adminWorkerConnectOnboard as unknown as Handler],
  ['POST /api/admin/jobs/[id]/approve', adminJobApprove as unknown as Handler],
  ['POST /api/admin/jobs/[id]/reassign', adminJobReassign as unknown as Handler],
  ['POST /api/admin/payouts/[id]/mark-mailed', adminMarkMailed as unknown as Handler],
]

let saved: string | undefined
let queries = 0
beforeEach(() => {
  saved = process.env.PAYOUTS_ENABLED
  queries = 0
  __setDbForTests(
    createFakeDb(() => {
      queries++
      throw new Error('a paused money route must not query the database')
    }).db,
  )
})
afterEach(() => {
  if (saved === undefined) delete process.env.PAYOUTS_ENABLED
  else process.env.PAYOUTS_ENABLED = saved
  __setDbForTests(null)
})

function moneyRequest(): never {
  return new Request('http://x/api/money', {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Bearer anything' },
    body: JSON.stringify({ amountCents: 5000, method: 'stripe', toWorkerId: 2 }),
  }) as never
}

for (const [name, handler] of MONEY_ROUTES) {
  test(`${name} returns 503 "Payouts are paused" when PAYOUTS_ENABLED is unset`, async () => {
    delete process.env.PAYOUTS_ENABLED
    const res = await handler(moneyRequest(), params as never)
    assert.equal(res.status, 503)
    assert.deepEqual(await res.json(), { error: PAYOUTS_PAUSED_ERROR })
    assert.equal(queries, 0)
  })

  test(`${name} stays paused for PAYOUTS_ENABLED=false`, async () => {
    process.env.PAYOUTS_ENABLED = 'false'
    const res = await handler(moneyRequest(), params as never)
    assert.equal(res.status, 503)
  })

  test(`${name} passes the switch when PAYOUTS_ENABLED=true (then fails auth, not the switch)`, async () => {
    process.env.PAYOUTS_ENABLED = 'true'
    let res: Response
    try {
      res = await handler(moneyRequest(), params as never)
    } catch {
      return // reached past the switch into the (fake) DB — that is the point
    }
    assert.notEqual(res.status, 503)
  })
}

test('payoutsEnabled: off unless explicitly on', () => {
  assert.equal(payoutsEnabled({}), false)
  assert.equal(payoutsEnabled({ PAYOUTS_ENABLED: '' }), false)
  assert.equal(payoutsEnabled({ PAYOUTS_ENABLED: '0' }), false)
  assert.equal(payoutsEnabled({ PAYOUTS_ENABLED: 'false' }), false)
  assert.equal(payoutsEnabled({ PAYOUTS_ENABLED: 'ture' }), false)
  assert.equal(payoutsEnabled({ PAYOUTS_ENABLED: 'true' }), true)
  assert.equal(payoutsEnabled({ PAYOUTS_ENABLED: ' TRUE ' }), true)
  assert.equal(payoutsEnabled({ PAYOUTS_ENABLED: '1' }), true)
  assert.equal(payoutsPausedResponse({ PAYOUTS_ENABLED: 'on' }), null)
  assert.equal(payoutsPausedResponse({})?.status, 503)
})
