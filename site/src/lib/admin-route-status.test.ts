import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseAdminRouteStatus, eligibleBlockStatusForRouteStatus } from './admin-route-status'

test('accepts exactly the database route statuses', () => {
  assert.equal(parseAdminRouteStatus('draft'), 'draft')
  assert.equal(parseAdminRouteStatus('published'), 'published')
  assert.equal(parseAdminRouteStatus('archived'), 'archived')
})

test('rejects malformed status values', () => {
  for (const value of [undefined, null, 'PUBLISHED', 'active', 1, true]) {
    assert.equal(parseAdminRouteStatus(value), null)
  }
})

test('maps route visibility to eligible linked-block state', () => {
  assert.equal(eligibleBlockStatusForRouteStatus('published'), 'available')
  assert.equal(eligibleBlockStatusForRouteStatus('draft'), 'funded')
  assert.equal(eligibleBlockStatusForRouteStatus('archived'), 'funded')
})
