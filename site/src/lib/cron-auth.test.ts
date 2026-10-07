import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'

process.env.DATABASE_URL ??= 'postgres://test:test@localhost/test'

import { constantTimeEqual, cronAuthFailure } from './cron-auth'
import { GET as buildingFootprints } from '@/app/api/cron/building-footprints/route'
import { GET as routeReleases } from '@/app/api/cron/route-releases/route'

const saved = process.env.CRON_SECRET
afterEach(() => {
  if (saved === undefined) delete process.env.CRON_SECRET
  else process.env.CRON_SECRET = saved
})

const req = (auth?: string) =>
  new Request('http://x/api/cron/x', { headers: auth ? { authorization: auth } : {} })

test('constantTimeEqual: equal, unequal, and different-length strings', () => {
  assert.equal(constantTimeEqual('Bearer s3cret', 'Bearer s3cret'), true)
  assert.equal(constantTimeEqual('Bearer s3cret', 'Bearer s3creT'), false)
  // Different lengths must not throw (timingSafeEqual would on raw buffers).
  assert.equal(constantTimeEqual('Bearer', 'Bearer s3cret-and-much-longer'), false)
  assert.equal(constantTimeEqual('', 'Bearer s3cret'), false)
})

test('cronAuthFailure: fails closed with 503 when CRON_SECRET is unset', () => {
  assert.equal(cronAuthFailure(req('Bearer x'), undefined)?.status, 503)
  assert.equal(cronAuthFailure(req('Bearer x'), '')?.status, 503)
})

test('cronAuthFailure: 401 for missing, wrong, or wrong-length bearer; null for the right one', () => {
  assert.equal(cronAuthFailure(req(), 's3cret')?.status, 401)
  assert.equal(cronAuthFailure(req('Bearer nope!!'), 's3cret')?.status, 401)
  assert.equal(cronAuthFailure(req('Bearer s3cret-extra'), 's3cret')?.status, 401)
  assert.equal(cronAuthFailure(req('s3cret'), 's3cret')?.status, 401)
  assert.equal(cronAuthFailure(req('Bearer s3cret'), 's3cret'), null)
})

for (const [name, handler] of [
  ['cron/building-footprints', buildingFootprints],
  ['cron/route-releases', routeReleases],
] as const) {
  test(`${name}: uses the guard (503 unset, 401 wrong secret)`, async () => {
    delete process.env.CRON_SECRET
    assert.equal((await handler(req('Bearer x') as never)).status, 503)
    process.env.CRON_SECRET = 'route-secret'
    assert.equal((await handler(req('Bearer wrong') as never)).status, 401)
    assert.equal((await handler(req('Bearer route-secre') as never)).status, 401)
  })
}
