import assert from 'node:assert/strict'
import test from 'node:test'

import type { PaidJobCandidate } from './paid-job-ttl'
import {
  claimProgressReminder,
  claimStartReminder,
  expireAbandonedScans,
  expireStaleSightings,
  flagStalled,
  releasePaidClaim,
} from './paid-job-sweep-db'
import { reopenReleaseSlot } from './route-release-slot'
import { recordingDb, when } from './testing/recording-db'

const NOW = '2026-10-08T00:30:00.000Z'
const flat = (sql: string) => sql.replace(/[()]/g, '')

const claimed: PaidJobCandidate = {
  id: 11,
  blockId: 22,
  workerId: 7,
  status: 'claimed',
  payoutAmount: '10000',
  claimedAt: '2026-10-07T00:00:00.000Z',
  startedAt: null,
  startReminderSentAt: null,
  progressReminderSentAt: null,
  stalledAt: null,
}

test('releasePaidClaim: guarded job release, block back to its route status, queue slot reopened', async () => {
  const { db, queries } = recordingDb(
    when(/^update "jobs"/, [[11]]),
    when(/from "blocks" left join "routes"/, [['published', 'open']]),
    when(/^update "route_release_routes"/, [[501, 61]]),
  )
  const result = await releasePaidClaim(db, claimed, NOW)
  assert.deepEqual(result, { released: true, slotReopened: true })

  const [jobUpdate, blockRead, blockUpdate, slotUpdate, batchUpdate] = queries
  // The canonical releaseJobPatch, for a paid job → available.
  assert.match(jobUpdate.sql, /^update "jobs" set "worker_id" = \$\d+, "status" = \$\d+, "claimed_at" = \$\d+, "started_at" = \$\d+, "payout_status" = \$\d+/)
  assert.ok(jobUpdate.params.includes('available'))
  const jobWhere = flat(jobUpdate.sql.slice(jobUpdate.sql.indexOf(' where ')))
  assert.match(jobWhere, /"jobs"\."status" = \$\d+ and "jobs"\."worker_id" = \$\d+ and "jobs"\."started_at" is null/)
  assert.match(jobWhere, /"jobs"\."claimed_at" < \$\d+::timestamptz - make_intervalhours => \$\d+::int/)
  assert.ok(jobUpdate.params.includes('claimed'))
  assert.ok(jobUpdate.params.includes(24))

  assert.match(blockRead.sql, /from "blocks" left join "routes"/)
  assert.match(blockUpdate.sql, /^update "blocks" set "status" = \$1/)
  assert.equal(blockUpdate.params[0], 'available', 'published + open route → block available again')
  assert.match(blockUpdate.sql, /not exists \(select 1 from jobs j where j\.block_id = "blocks"\."id"/)

  assert.match(slotUpdate.sql, /^update "route_release_routes" set "status" = \$1, "accepted_by_worker_id" = \$2, "accepted_offer_id" = \$3, "accepted_at" = \$4/)
  assert.equal(slotUpdate.params[0], 'open')
  assert.match(flat(slotUpdate.sql), /"job_id" = \$\d+ and "route_release_routes"\."status" = \$\d+/)
  assert.match(slotUpdate.sql, /exists \(select 1 from jobs j where j\.id = \$\d+ and j\.status = 'available'\)/)
  assert.ok(slotUpdate.params.includes(11))

  // The batch is re-activated only if it had completed (every slot taken).
  assert.match(batchUpdate.sql, /^update "route_release_batches" set "status" = \$1, "next_wave_at" = \$2/)
  assert.equal(batchUpdate.params[0], 'active')
  assert.ok(batchUpdate.params.includes('completed'))
  assert.ok(batchUpdate.params.includes(61))
})

test('releasePaidClaim: a job the worker started meanwhile (0 rows) is left alone — no block or slot change', async () => {
  const { db, queries } = recordingDb()
  const result = await releasePaidClaim(db, claimed, NOW)
  assert.deepEqual(result, { released: false, slotReopened: false })
  assert.equal(queries.length, 1)
})

test('releasePaidClaim: a job that was never on the release queue reports no slot reopened', async () => {
  const { db, queries } = recordingDb(when(/^update "jobs"/, [[11]]))
  const result = await releasePaidClaim(db, claimed, NOW)
  assert.deepEqual(result, { released: true, slotReopened: false })
  assert.ok(!queries.some((q) => /route_release_batches/.test(q.sql)), 'no batch touched without a slot')
})

test('reopenReleaseSlot: only a slot still `claimed` whose job is `available` again', async () => {
  const { db, queries } = recordingDb()
  assert.equal(await reopenReleaseSlot(db, 11, NOW), null)
  assert.match(queries[0].sql, /j\.status = 'available'/)
})

test('reminder stamps are claimed atomically, once per claim/run', async () => {
  const { db, queries } = recordingDb(when(/^update "jobs"/, [[11]]))
  assert.equal(await claimStartReminder(db, claimed, NOW), true)
  assert.match(queries[0].sql, /^update "jobs" set "start_reminder_sent_at" = \$1/)
  assert.match(queries[0].sql, /"jobs"\."start_reminder_sent_at" is null or "jobs"\."start_reminder_sent_at" < "jobs"\."claimed_at"/)

  const running = { ...claimed, status: 'in_progress', startedAt: '2026-10-07T01:00:00.000Z' }
  await claimProgressReminder(db, running, NOW)
  assert.match(queries[1].sql, /"progress_reminder_sent_at" < coalesce\("jobs"\."started_at", "jobs"\."claimed_at"\)/)
  assert.ok(queries[1].params.includes('in_progress'))

  const { db: none } = recordingDb()
  assert.equal(await claimStartReminder(none, claimed, NOW), false, '0 rows → already sent, no push')
})

test('flagStalled: sets stalled_at on an in-progress job; never touches status or worker', async () => {
  const { db, queries } = recordingDb(when(/^update "jobs"/, [[11]]))
  const running = { ...claimed, status: 'in_progress', startedAt: '2026-10-07T01:00:00.000Z' }
  assert.equal(await flagStalled(db, running, NOW), true)
  const set = queries[0].sql.slice(0, queries[0].sql.indexOf(' where '))
  assert.equal(set, 'update "jobs" set "updated_at" = $1, "stalled_at" = $2')
})

test('expireAbandonedScans: in_progress scans older than 7 days → abandoned (status only)', async () => {
  const { db, queries } = recordingDb(when(/^update "route_scans"/, [[1], [2]]))
  assert.deepEqual(await expireAbandonedScans(db, NOW), [1, 2])
  const q = queries[0]
  assert.match(q.sql, /^update "route_scans" set "status" = \$1, "updated_at" = \$2 where/)
  assert.equal(q.params[0], 'abandoned')
  assert.match(q.sql, /"route_scans"\."phase" <> 'complete'/)
  assert.match(q.sql, /make_interval\(days => \$\d+::int\)/)
  assert.ok(q.params.includes(7))
  assert.ok(q.params.includes('in_progress'))
})

test('expireStaleSightings: pending sightings older than 30 days get map_expires_at — nothing deleted, status kept', async () => {
  const { db, queries } = recordingDb(when(/^update "community_reports"/, [[115]]))
  assert.deepEqual(await expireStaleSightings(db, NOW), [115])
  const q = queries[0]
  assert.match(q.sql, /^update "community_reports" set "map_expires_at" = \$1, "updated_at" = \$2 where/)
  assert.doesNotMatch(q.sql, /"status" = \$\d+,/, 'status is not rewritten')
  assert.match(q.sql, /"community_reports"\."map_expires_at" is null/)
  for (const p of ['sighting', 'pending', 30]) assert.ok(q.params.includes(p), `param ${p}`)
})
