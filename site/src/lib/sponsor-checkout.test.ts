import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  BLOCK_PRICE_DOLLARS,
  blocksFromApi,
  buildSponsorCheckoutPayload,
  parseReservation,
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

test('specific blocks that exist are sponsored by id', () => {
  const payload = buildSponsorCheckoutPayload({
    mode: 'specific',
    selectedBlocks: [{ id: 7, code: 'NL-N01' }, { id: '9', code: 'NL-N02' }],
    generalBlockCount: 1,
    availableBlockCount: 10,
    shared,
  })
  assert.deepEqual(payload, { ...shared, blockIds: [7, 9] })
})

test('upcoming route blocks are a reservation, never a NaN block id', () => {
  const payload = buildSponsorCheckoutPayload({
    mode: 'specific',
    selectedBlocks: [
      { id: 'upcoming-NL-N01', code: 'NL-N01' },
      { id: 'upcoming-NL-N02', code: 'NL-N02' },
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
