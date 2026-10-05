import { test } from 'node:test'
import assert from 'node:assert/strict'
import { stripeKeyMode } from './key-mode'

// Run with: npx tsx --test src/lib/stripe/key-mode.test.ts

test('stripeKeyMode: live and test keys of every kind', () => {
  assert.equal(stripeKeyMode('sk_live_abc'), 'live')
  assert.equal(stripeKeyMode('rk_live_abc'), 'live')
  assert.equal(stripeKeyMode('pk_live_abc'), 'live')
  assert.equal(stripeKeyMode('sk_test_abc'), 'test')
  assert.equal(stripeKeyMode('pk_test_abc'), 'test')
})

test('stripeKeyMode: unset is missing, anything else is unknown', () => {
  assert.equal(stripeKeyMode(undefined), 'missing')
  assert.equal(stripeKeyMode(''), 'missing')
  assert.equal(stripeKeyMode('whsec_abc'), 'unknown')
  assert.equal(stripeKeyMode('sk_live'), 'unknown')
})
