import { test } from 'node:test'
import assert from 'node:assert/strict'

process.env.ADMIN_EMAILS = 'boss@example.org'

import { mapWorkerToMobile } from './worker-mapper'

const row = {
  id: 7,
  name: 'Wendy Worker',
  email: 'wendy@example.org',
  phone: '',
  emailVerified: true,
  addressStreet: null,
  addressCity: null,
  addressState: null,
  addressZip: null,
  signupRankingsGlobal: '120',
  signupRankingsCountry: '95',
  signupRankingsState: '12',
  signupRankingsCity: '4',
  signupRankingsNeighborhood: '2',
  createdAt: '2026-01-01 00:00:00+00',
  updatedAt: '2026-01-02 00:00:00+00',
}

test('missing address city/state is null, not Chicago/IL', () => {
  const out = mapWorkerToMobile(row)
  assert.equal(out.address.city, null)
  assert.equal(out.address.state, null)
  assert.equal(out.address.street, '')
  assert.equal(out.address.zip, '')
})

test('signupRankings carries country', () => {
  assert.equal(mapWorkerToMobile(row).signupRankings.country, 95)
})

test('role follows roleFor: verified ADMIN_EMAILS address is admin', () => {
  assert.equal(mapWorkerToMobile(row).role, 'worker')
  assert.equal(mapWorkerToMobile({ ...row, email: 'boss@example.org' }).role, 'admin')
  assert.equal(mapWorkerToMobile({ ...row, email: 'boss@example.org', emailVerified: false }).role, 'worker')
})
