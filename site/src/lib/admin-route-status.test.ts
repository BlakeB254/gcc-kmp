import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  effectiveRouteState,
  eligibleBlockStatusForRoute,
  eligibleBlockStatusForRouteStatus,
  isClaimableRoute,
  parseAdminClaimStatus,
  parseAdminRouteStatus,
} from './admin-route-status'

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

test('accepts exactly the database claim statuses', () => {
  assert.equal(parseAdminClaimStatus('closed'), 'closed')
  assert.equal(parseAdminClaimStatus('open'), 'open')
  assert.equal(parseAdminClaimStatus('scheduled'), 'scheduled')
})

test('rejects malformed claim status values', () => {
  for (const value of [undefined, null, 'OPEN', 'pending', 0, false]) {
    assert.equal(parseAdminClaimStatus(value), null)
  }
})

test('effectiveRouteState implements the plan state matrix', () => {
  // draft | archived — hidden regardless of claim switch
  assert.equal(effectiveRouteState({ status: 'draft', claimStatus: 'closed' }), 'hidden')
  assert.equal(effectiveRouteState({ status: 'archived', claimStatus: 'closed' }), 'hidden')
  // published rows
  assert.equal(effectiveRouteState({ status: 'published', claimStatus: 'closed' }), 'readable')
  assert.equal(effectiveRouteState({ status: 'published', claimStatus: 'open' }), 'claimable')
  assert.equal(effectiveRouteState({ status: 'published', claimStatus: 'scheduled' }), 'scheduled')
  // missing claim switch defaults to closed
  assert.equal(effectiveRouteState({ status: 'published' }), 'readable')
})

test('isClaimableRoute is true only for published + open', () => {
  assert.equal(isClaimableRoute({ status: 'published', claimStatus: 'open' }), true)
  assert.equal(isClaimableRoute({ status: 'published', claimStatus: 'closed' }), false)
  assert.equal(isClaimableRoute({ status: 'published', claimStatus: 'scheduled' }), false)
  assert.equal(isClaimableRoute({ status: 'draft', claimStatus: 'open' }), false)
  assert.equal(isClaimableRoute({ status: 'archived', claimStatus: 'open' }), false)
})

test('eligibleBlockStatusForRoute opens blocks only for published + open', () => {
  assert.equal(eligibleBlockStatusForRoute({ status: 'published', claimStatus: 'open' }), 'available')
  assert.equal(eligibleBlockStatusForRoute({ status: 'published', claimStatus: 'closed' }), 'funded')
  assert.equal(eligibleBlockStatusForRoute({ status: 'published', claimStatus: 'scheduled' }), 'funded')
  assert.equal(eligibleBlockStatusForRoute({ status: 'draft', claimStatus: 'closed' }), 'funded')
  assert.equal(eligibleBlockStatusForRoute({ status: 'archived', claimStatus: 'closed' }), 'funded')
})

test('maps route visibility to eligible linked-block state (legacy export)', () => {
  assert.equal(eligibleBlockStatusForRouteStatus('published'), 'available')
  assert.equal(eligibleBlockStatusForRouteStatus('draft'), 'funded')
  assert.equal(eligibleBlockStatusForRouteStatus('archived'), 'funded')
})
