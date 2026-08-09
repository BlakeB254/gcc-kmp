import assert from 'node:assert/strict'
import test from 'node:test'
import { NextRequest } from 'next/server'

import { PRIVATE_NO_STORE } from '@/lib/http/cache-policy'
import { GET as impactStats } from './impact-stats/route'
import { GET as sponsorWall } from './sponsor-wall/route'
import { GET as publicStats } from './stats/route'
import { GET as supportWall } from './support-wall/route'

test('public summary failures preserve their response contracts without shared caching', async () => {
  // Catches adding a public Cache-Control policy to fallback responses, which
  // lets a transient database/cache failure replace a valid CDN entry with zeros.
  const responses = await Promise.all([
    publicStats(),
    impactStats(),
    sponsorWall(),
    supportWall(new NextRequest('https://example.test/api/public/support-wall?view=recent&limit=20')),
  ])

  assert.deepEqual(responses.map((response) => response.status), [200, 200, 200, 500])
  assert.deepEqual(
    responses.map((response) => response.headers.get('Cache-Control')),
    [PRIVATE_NO_STORE, PRIVATE_NO_STORE, PRIVATE_NO_STORE, PRIVATE_NO_STORE],
  )

  assert.equal((await responses[0].json()).success, false)
  assert.equal((await responses[1].json()).workers.total, 0)
  assert.deepEqual((await responses[2].json()).sponsors, [])
  assert.deepEqual(await responses[3].json(), { error: 'Failed to fetch support wall data' })
})
