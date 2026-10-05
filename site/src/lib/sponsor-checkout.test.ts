import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  BLOCK_PRICE_DOLLARS,
  blocksFromApi,
  buildSponsorCheckoutPayload,
  parseReservation,
  renewalFromInvoice,
  reservationFromMetadata,
  stripeRecurring,
} from './sponsor-checkout'

// Rules for the $100 "Sponsor a Block" checkout on /sponsor. Run with:
//   npx tsx --test src/lib/sponsor-checkout.test.ts
// Pure — no database, no Stripe.

const shared = { frequency: 'one_time', name: 'Del-Kar Pharmacy', email: 'owner@example.com' }

// ── blocksFromApi ─────────────────────────────────────────────────────────

test('blocksFromApi: reads the list from `data`, which is what GET /api/blocks returns', () => {
  assert.deepEqual(blocksFromApi({ success: true, data: [{ id: 1 }], total: 1 }), [{ id: 1 }])
})

test('blocksFromApi: still accepts the older `blocks` key, and anything else is no blocks', () => {
  assert.deepEqual(blocksFromApi({ blocks: [{ id: 2 }] }), [{ id: 2 }])
  assert.deepEqual(blocksFromApi({ error: 'boom' }), [])
  assert.deepEqual(blocksFromApi(null), [])
})

// ── buildSponsorCheckoutPayload ───────────────────────────────────────────

test('available blocks are sponsored by id', () => {
  const payload = buildSponsorCheckoutPayload({
    mode: 'specific',
    selectedBlocks: [
      { id: 7, code: 'NL-N01', status: 'available' },
      { id: '9', code: 'NL-N02', status: 'available' },
    ],
    generalBlockCount: 1,
    availableBlockCount: 10,
    shared,
  })
  assert.deepEqual(payload, { ...shared, blockIds: [7, 9] })
})

test('a block that was already cleaned can be sponsored again, for its next cleaning', () => {
  const payload = buildSponsorCheckoutPayload({
    mode: 'specific',
    selectedBlocks: [
      { id: '3', code: 'NL-N03', status: 'completed' },
      { id: '4', code: 'NL-N04', status: 'available' },
    ],
    generalBlockCount: 1,
    availableBlockCount: 1,
    shared,
  })
  assert.deepEqual(payload, {
    ...shared,
    reservation: { blockCount: 2, blockCodes: ['NL-N03', 'NL-N04'] },
  })
})

test('upcoming route blocks are a reservation, never a NaN block id', () => {
  const payload = buildSponsorCheckoutPayload({
    mode: 'specific',
    selectedBlocks: [
      { id: 'upcoming-NL-N01', code: 'NL-N01', status: 'available' },
      { id: 'upcoming-NL-N02', code: 'NL-N02', status: 'available' },
    ],
    generalBlockCount: 1,
    availableBlockCount: 0,
    shared,
  })
  assert.deepEqual(payload, {
    ...shared,
    reservation: { blockCount: 2, blockCodes: ['NL-N01', 'NL-N02'] },
  })
})

test('"let GCC choose" assigns blocks now when enough are available', () => {
  const payload = buildSponsorCheckoutPayload({
    mode: 'general',
    selectedBlocks: [],
    generalBlockCount: 3,
    availableBlockCount: 5,
    shared,
  })
  assert.deepEqual(payload, { ...shared, allocationType: 'area', blockCount: 3 })
})

test('"let GCC choose" with no blocks available still checks out, as a reservation', () => {
  const payload = buildSponsorCheckoutPayload({
    mode: 'general',
    selectedBlocks: [],
    generalBlockCount: 1,
    availableBlockCount: 0,
    shared,
  })
  assert.deepEqual(payload, { ...shared, reservation: { blockCount: 1 } })
})

test('specific mode with nothing selected is not a checkout', () => {
  assert.equal(
    buildSponsorCheckoutPayload({
      mode: 'specific',
      selectedBlocks: [],
      generalBlockCount: 1,
      availableBlockCount: 0,
      shared,
    }),
    null,
  )
})

// ── parseReservation (server side) ────────────────────────────────────────

test('parseReservation: prices a reservation at $100 a block, whatever the client says', () => {
  assert.deepEqual(parseReservation({ blockCount: 3 }), {
    blockCount: 3,
    blockCodes: [],
    amountCents: 3 * BLOCK_PRICE_DOLLARS * 100,
  })
})

test('parseReservation: keeps only well-formed block codes', () => {
  const parsed = parseReservation({ blockCount: 2, blockCodes: ['NL-N01', '<script>', 'NL-N02', 7] })
  assert.deepEqual(parsed?.blockCodes, ['NL-N01', 'NL-N02'])
})

test('parseReservation: rejects a missing, fractional, zero or absurd count', () => {
  for (const bad of [undefined, null, {}, { blockCount: 0 }, { blockCount: 1.5 }, { blockCount: '2' }, { blockCount: 10001 }]) {
    assert.equal(parseReservation(bad), null, `expected ${JSON.stringify(bad)} to be rejected`)
  }
})

// ── stripeRecurring ───────────────────────────────────────────────────────

test('stripeRecurring: a one-time gift is not a subscription', () => {
  assert.equal(stripeRecurring('one_time'), null)
  assert.equal(stripeRecurring(undefined), null)
  assert.equal(stripeRecurring('daily'), null)
})

test('stripeRecurring: weekly, every-two-weeks and monthly bill on the schedule the sponsor chose', () => {
  assert.deepEqual(stripeRecurring('weekly'), { interval: 'week', interval_count: 1 })
  assert.deepEqual(stripeRecurring('biweekly'), { interval: 'week', interval_count: 2 })
  assert.deepEqual(stripeRecurring('monthly'), { interval: 'month', interval_count: 1 })
})

// ── reservationFromMetadata (webhook side) ────────────────────────────────

test('reservationFromMetadata: reads back what the checkout stored on the Stripe session', () => {
  assert.deepEqual(
    reservationFromMetadata({ reservedBlockCount: '2', reservedBlockCodes: 'NL-N01,NL-N02' }),
    { blockCount: 2, blockCodes: 'NL-N01,NL-N02' },
  )
  assert.deepEqual(reservationFromMetadata({ reservedBlockCount: '1', reservedBlockCodes: '' }), {
    blockCount: 1,
    blockCodes: null,
  })
})

test('reservationFromMetadata: an ordinary donation is not a reservation', () => {
  for (const meta of [null, undefined, {}, { reservedBlockCount: '' }, { reservedBlockCount: '0' }, { reservedBlockCount: 'abc' }]) {
    assert.equal(reservationFromMetadata(meta), null, `expected ${JSON.stringify(meta)} to be no reservation`)
  }
})

test('reservationFromMetadata: block codes that are not codes are dropped', () => {
  assert.deepEqual(
    reservationFromMetadata({ reservedBlockCount: '2', reservedBlockCodes: 'NL-N01,<b>x</b>' }),
    { blockCount: 2, blockCodes: 'NL-N01' },
  )
})

// ── renewalFromInvoice (webhook side) ─────────────────────────────────────

const cycleInvoice = {
  id: 'in_123',
  billing_reason: 'subscription_cycle',
  amount_paid: 10000,
  currency: 'usd',
  parent: {
    subscription_details: {
      subscription: 'sub_abc',
      metadata: { sponsorId: '42', frequency: 'weekly', displayName: 'Del-Kar Pharmacy', isAnonymous: 'false' },
    },
  },
}

test('renewalFromInvoice: a paid renewal becomes a payment to record', () => {
  assert.deepEqual(renewalFromInvoice(cycleInvoice), {
    sponsorId: 42,
    amountCents: 10000,
    currency: 'usd',
    subscriptionId: 'sub_abc',
    frequency: 'weekly',
    displayName: 'Del-Kar Pharmacy',
    isAnonymous: false,
    reservation: null,
  })
})

test('renewalFromInvoice: a renewal of a block reservation owes another cleaning', () => {
  const invoice = {
    ...cycleInvoice,
    parent: {
      subscription_details: {
        subscription: { id: 'sub_obj' },
        metadata: { sponsorId: '42', frequency: 'monthly', reservedBlockCount: '2', reservedBlockCodes: 'NL-N01,NL-N02' },
      },
    },
  }
  const renewal = renewalFromInvoice(invoice)
  assert.equal(renewal?.subscriptionId, 'sub_obj')
  assert.deepEqual(renewal?.reservation, { blockCount: 2, blockCodes: 'NL-N01,NL-N02' })
})

test('renewalFromInvoice: the first invoice is not a renewal — checkout already recorded it', () => {
  assert.equal(renewalFromInvoice({ ...cycleInvoice, billing_reason: 'subscription_create' }), null)
})

test('renewalFromInvoice: nothing to record without a payment, a subscription, or a known sponsor', () => {
  assert.equal(renewalFromInvoice({ ...cycleInvoice, amount_paid: 0 }), null)
  assert.equal(renewalFromInvoice({ ...cycleInvoice, parent: null }), null)
  assert.equal(
    renewalFromInvoice({
      ...cycleInvoice,
      parent: { subscription_details: { subscription: 'sub_abc', metadata: { frequency: 'weekly' } } },
    }),
    null,
  )
  assert.equal(
    renewalFromInvoice({
      ...cycleInvoice,
      parent: { subscription_details: { subscription: 'sub_abc', metadata: { sponsorId: 'abc' } } },
    }),
    null,
  )
})
