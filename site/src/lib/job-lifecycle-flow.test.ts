/**
 * The block state machine around a job (2026-10-18 audit): one claim for both
 * claim routes, approval closing the block, deny → rework, admin release, and
 * the evidence floor a reworked or routeless job must clear.
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import {
  APPROVAL_CLOSABLE_BLOCK_STATUSES,
  activePaidJobConflictMessage,
  startClaimedJob,
  isBlockTakenViolation,
  isWorkerBusyViolation,
  takeBlockForJob,
  acceptOfferedJob,
  declineClaimedJob,
  blockClaimRefusal,
  claimJobForApproval,
  claimWork,
  completeJob,
  denyJobForRework,
  canonicalEvidenceUrl,
  isOwnEvidenceUrl,
  hasEvidencePairSince,
  releaseJobToPool,
  reworkSince,
  workFloor,
} from './job-lifecycle'
import { count } from 'drizzle-orm'
import { transactions } from '@/db/schema'
import { fundedBlockRow, isFundedBlockRow } from './sponsor-funded'
import { recordingDb, when, type RecordedQuery, type Responder } from './testing/recording-db'

const NOW = '2026-10-18T12:00:00.000Z'
const flat = (sql: string) => sql.replace(/[()]/g, '')
const writes = (queries: RecordedQuery[]) => queries.filter((q) => /^(update|insert|delete|with)/.test(q.sql))

// A full jobs row in pg-proxy array form (column order of the jobs table).
function jobRow(o: { id?: number; blockId?: number; status?: string; workerId?: number | null; payoutAmount?: string | null } = {}) {
  return [
    o.id ?? 5, o.blockId ?? 10, o.workerId === undefined ? 7 : o.workerId, o.status ?? 'claimed',
    NOW, null, null, // claimedAt, startedAt, completedAt
    null, null, null, // beforePhotos, afterPhotos, gpsPath
    o.payoutAmount === undefined ? '2500' : o.payoutAmount, null, null, null,
    null, null, // rating, ratingNotes
    null, // performedByAdminId
    null, null, null, null, null, // review fields
    NOW, NOW,
    null, null, null, null, // completionScanId, startReminderSentAt, progressReminderSentAt, stalledAt
  ]
}

// A full blocks row in pg-proxy array form (column order of the blocks table).
function blockRow(o: { status?: string; routeId?: number | null } = {}) {
  return [
    10, 'B-10', 'Block 10', null, o.status ?? 'available', 1, o.routeId === undefined ? 12 : o.routeId,
    null, null, null, null, null, null, null, null, null, null, null, // neighborhood … side
    true, '100', '0', // includesAlley, fundingGoal, fundingCurrent
    null, null, null, null, null, null, null, // funded…, preferred…, completedById, completedAt
    NOW, NOW,
  ]
}

const ACTIVE_JOB_READ = /^select "id" from "jobs" where \(\("jobs"\."worker_id"/
const TAKE = /^with "flipped" as \(update "blocks"/

type ClaimFixture = {
  worker?: 'approved' | 'pending' | null
  activeJob?: boolean
  jobStatus?: string | null
  block?: { status?: string; routeId?: number | null } | null
  openJobId?: number | null
  /** What the one take statement does. */
  take?: 'ok' | 'not_takeable' | 'abort' | 'unique' | 'unique_worker' | 'boom'
  /** After an abort: is the worker busy (the read-only "why")? */
  busyAfterAbort?: boolean
}

function claimDb(f: ClaimFixture = {}) {
  let aborted = false
  const responders: Responder[] = [
    when(/^select "id", "verification_status" from "workers"/, f.worker === null ? [] : [[7, f.worker ?? 'approved']]),
    (q) => (ACTIVE_JOB_READ.test(q.sql) ? ((aborted ? f.busyAfterAbort : f.activeJob) ? [[99]] : []) : undefined),
    when(/^select "id", "block_id", "status" from "jobs"/, f.jobStatus === null ? [] : [[5, 10, f.jobStatus ?? 'available']]),
    when(/^select "id" from "jobs" where \(\("jobs"\."block_id"/, f.openJobId ? [[f.openJobId]] : []),
    when(/^select "id", "code", "name".* from "blocks"/, f.block === null ? [] : [blockRow(f.block ?? {})]),
    when(/from "routes"/, [['2500']]),
    (q) => {
      if (!TAKE.test(q.sql)) return undefined
      switch (f.take ?? 'ok') {
        case 'ok':
          return [[1, 77]]
        case 'not_takeable':
          return [[0, null]]
        case 'abort':
          aborted = true
          throw new Error('invalid input syntax for type integer: "gcc statement aborted: take 10"')
        case 'unique':
          throw Object.assign(new Error('duplicate key value violates unique constraint'), {
            code: '23505',
            constraint: 'jobs_one_active_paid_job_per_block',
          })
        case 'unique_worker':
          throw Object.assign(new Error('duplicate key value violates unique constraint'), {
            code: '23505',
            constraint: 'jobs_one_active_paid_job_per_worker',
          })
        default:
          throw new Error('boom')
      }
    },
    when(/^select .* from "jobs" where "jobs"\."id" = \$1 limit/, [jobRow({ id: 77 })]),
  ]
  return recordingDb(...responders)
}

// ── claimWork: the same rules for the web and the app ───────────────────────

test('claimWork: unknown worker 404, unverified worker 403 — before anything is read or written', async () => {
  for (const [worker, status] of [[null, 404], ['pending', 403]] as const) {
    const { db, queries } = claimDb({ worker })
    const r = await claimWork(db, { jobId: 5 }, 7, NOW)
    assert.equal(r.ok, false)
    assert.equal(!r.ok && r.status, status)
    assert.equal(writes(queries).length, 0)
  }
})

test('claimWork: a worker with an active job (claimed / in_progress / pending_review) cannot claim another', async () => {
  for (const target of [{ jobId: 5 }, { blockId: 10 }]) {
    const { db, queries } = claimDb({ activeJob: true })
    const r = await claimWork(db, target, 7, NOW)
    assert.deepEqual(r, { ok: false, status: 409, message: 'You already have an active job. Complete it first.' })
    const active = queries.find((q) => ACTIVE_JOB_READ.test(q.sql))
    assert.ok(active)
    for (const s of ['claimed', 'in_progress', 'pending_review']) assert.ok(active.params.includes(s))
    assert.equal(writes(queries).length, 0)
  }
})

test('claimWork (app path): a job that is not available → 409, nothing written', async () => {
  const { db, queries } = claimDb({ jobStatus: 'claimed' })
  const r = await claimWork(db, { jobId: 5 }, 7, NOW)
  assert.deepEqual(r, { ok: false, status: 409, message: 'Job is not available' })
  assert.equal(writes(queries).length, 0)
})

test('claimWork: only an available block on a route is claimable — funded, in-progress, completed and routeless are refused', async () => {
  const cases: Array<[ClaimFixture['block'], RegExp]> = [
    [{ status: 'funded' }, /funded but not yet available/],
    [{ status: 'in_progress' }, /already been claimed/],
    [{ status: 'completed' }, /not available for claiming/],
    [{ status: 'available', routeId: null }, /not linked to a route/],
  ]
  for (const target of [{ jobId: 5 }, { blockId: 10 }]) {
    for (const [block, message] of cases) {
      const { db, queries } = claimDb({ block })
      const r = await claimWork(db, target, 7, NOW)
      assert.equal(r.ok, false, `${JSON.stringify(target)} ${block?.status}`)
      assert.equal(!r.ok && r.status, 409)
      assert.match(!r.ok ? r.message : '', message)
      assert.equal(writes(queries).length, 0, 'no block flip, no job write')
    }
  }
})

test('claimWork (app path): block flip + job claim are ONE statement (neon-http: one statement = one transaction)', async () => {
  const { db, queries } = claimDb()
  const r = await claimWork(db, { jobId: 5 }, 7, NOW)
  assert.equal(r.ok, true)
  assert.equal(r.ok && r.block.status, 'in_progress')
  const ws = writes(queries)
  assert.equal(ws.length, 1, 'no second write — nothing to compensate')
  const { sql, params } = ws[0]
  assert.match(flat(sql), /^with "flipped" as update "blocks" set "status" = \$1, "updated_at" = \$2 where "blocks"\."id" = \$3 and "blocks"\."status" in \$4 returning "id", "taken"/)
  assert.equal(params[0], 'in_progress')
  assert.equal(params[3], 'available')
  // The job write runs only off the flip, and only for a free worker.
  assert.match(sql, /"taken" as \(update "jobs" set "worker_id" = \$\d+, "status" = \$\d+, "claimed_at" = \$\d+, "payout_amount" = \$\d+/)
  assert.match(sql, /exists \(select 1 from "flipped"\) and not exists \(select 1 from jobs w where w\.worker_id = \$\d+\s+and w\.id <> \$\d+\s+and w\.status in \('claimed', 'in_progress', 'pending_review'\)\)/)
  // Flipped but no job → the cast aborts (rolls back) the whole statement.
  assert.match(sql, /when exists \(select 1 from "flipped"\) then \(\$\d+ \|\| \(select "id" from "flipped"\)\)::int/)
  assert.ok(params.includes(5), 'the target job')
  assert.ok(params.includes('2500'), 'route payout written on the claimed row (Grok #3)')
})

test('claimWork (web path): no open job → INSERT … SELECT … FROM "flipped" at the ROUTE payout, in the same statement', async () => {
  const { db, queries } = claimDb()
  const r = await claimWork(db, { blockId: 10 }, 7, NOW)
  assert.equal(r.ok && r.job.id, 77)
  const ws = writes(queries)
  assert.equal(ws.length, 1)
  const { sql, params } = ws[0]
  assert.match(sql, /"taken" as \(insert into "jobs" \("id", "block_id", "worker_id", "status", "claimed_at", .*\) select nextval\(pg_get_serial_sequence\('jobs', 'id'\)\), \$\d+::int, \$\d+::int, \$\d+::enum_jobs_status, \$\d+::timestamptz/)
  assert.match(sql, /from "flipped" where true and not exists \(select 1 from jobs w where w\.worker_id = \$\d+/)
  assert.ok(params.includes('2500'), 'routes.payout_amount, not blocks.funding_goal')
  assert.ok(params.includes('claimed'))
})

test('claimWork (web path): the block’s open job (e.g. one a release put back) is claimed — at the route payout — not duplicated', async () => {
  const { db, queries } = claimDb({ openJobId: 6 })
  const r = await claimWork(db, { blockId: 10 }, 7, NOW)
  assert.equal(r.ok, true)
  const take = queries.find((q) => TAKE.test(q.sql))!
  assert.doesNotMatch(take.sql, /insert into "jobs"/)
  assert.ok(take.params.includes(6))
  assert.match(take.sql, /"payout_amount" = \$\d+/)
  assert.ok(take.params.includes('2500'))
})

test('claimWork: block not takeable any more (0 rows flipped) → 409, nothing else written', async () => {
  const { db, queries } = claimDb({ take: 'not_takeable' })
  const r = await claimWork(db, { jobId: 5 }, 7, NOW)
  assert.deepEqual(r, { ok: false, status: 409, message: 'This block has already been claimed by another worker' })
  assert.equal(writes(queries).length, 1)
})

test('claimWork: flipped but the job write lost → the statement aborted (nothing committed) → 409 with the reason', async () => {
  const busy = claimDb({ take: 'abort', busyAfterAbort: true })
  assert.deepEqual(await claimWork(busy.db, { blockId: 10 }, 7, NOW), {
    ok: false,
    status: 409,
    message: 'You already have an active job. Complete it first.',
  })
  const lost = claimDb({ take: 'abort' })
  assert.deepEqual(await claimWork(lost.db, { jobId: 5 }, 7, NOW), { ok: false, status: 409, message: 'Job was just claimed by someone else' })
  // No compensation statement: the abort already rolled the flip back.
  assert.equal(writes(lost.queries).length, 1)
})

test('claimWork: a second active paid job on the block (unique index, migration 0055) → 409, not 500', async () => {
  const { db, queries } = claimDb({ take: 'unique' })
  assert.deepEqual(await claimWork(db, { blockId: 10 }, 7, NOW), {
    ok: false,
    status: 409,
    message: 'This block has already been claimed by another worker',
  })
  assert.equal(writes(queries).length, 1)
})

test('claimWork: a second active paid job for the worker (per-worker index, migration 0055) → 409 "active job", not 500', async () => {
  const { db, queries } = claimDb({ take: 'unique_worker' })
  assert.deepEqual(await claimWork(db, { blockId: 10 }, 7, NOW), {
    ok: false,
    status: 409,
    message: 'You already have an active job. Complete it first.',
  })
  assert.equal(writes(queries).length, 1)
})

test('claimWork: any other database error propagates (the route answers 500); nothing to undo', async () => {
  const { db, queries } = claimDb({ take: 'boom' })
  await assert.rejects(claimWork(db, { blockId: 10 }, 7, NOW))
  assert.equal(writes(queries).length, 1)
})

test('isBlockTakenViolation: only unique_violation (on the per-block index, when named)', () => {
  assert.equal(isBlockTakenViolation({ cause: { code: '23505', constraint: 'jobs_one_active_paid_job_per_block' } }), true)
  assert.equal(isBlockTakenViolation({ code: '23505' }), true)
  assert.equal(isBlockTakenViolation({ cause: { code: '23505', constraint: 'jobs_completion_scan_id_unique' } }), false)
  assert.equal(isBlockTakenViolation({ cause: { code: '23503' } }), false)
  assert.equal(isBlockTakenViolation({ cause: { code: '23505', constraint: 'jobs_one_active_paid_job_per_worker' } }), false)
  assert.equal(isWorkerBusyViolation({ cause: { code: '23505', constraint: 'jobs_one_active_paid_job_per_worker' } }), true)
  assert.equal(isWorkerBusyViolation({ cause: { code: '23505', constraint: 'jobs_one_active_paid_job_per_block' } }), false)
})

test('blockClaimRefusal: only available is claimable', () => {
  assert.equal(blockClaimRefusal({ status: 'available' }), null)
  for (const s of ['funded', 'in_progress', 'completed', null] as const) assert.notEqual(blockClaimRefusal({ status: s }), null)
})

test('acceptOfferedJob / admin assignment: the same one-statement take, from funded/completed too', async () => {
  const { db, queries } = recordingDb(
    when(TAKE, [[1, 5]]),
    when(/^select .* from "jobs" where "jobs"\."id" = \$1 limit/, [jobRow({ id: 5, status: 'claimed' })]),
  )
  const r = await acceptOfferedJob(db, { jobId: 5, blockId: 10, workerId: 7, now: NOW })
  assert.equal(r.ok && r.job.status, 'claimed')
  const take = queries.find((q) => TAKE.test(q.sql))!
  assert.match(take.sql, /"blocks"\."status" in \(\$\d+, \$\d+, \$\d+\)/)
  for (const s of ['available', 'funded', 'completed']) assert.ok(take.params.includes(s), s)
  assert.match(take.sql, /not exists \(select 1 from jobs w where w\.worker_id/, 'offer accept keeps the one-active-job rule')
  // Admin assignment (ensureJobForRouteWorker) passes requireWorkerFree: false — no worker clause.
  const admin = recordingDb(when(TAKE, [[1, 77]]), when(/^select .* from "jobs" where "jobs"\."id" = \$1 limit/, [jobRow({ id: 77 })]))
  await takeBlockForJob(admin.db, {
    blockId: 10,
    workerId: 7,
    now: NOW,
    fromStatuses: ['available', 'funded', 'completed', 'in_progress'],
    requireWorkerFree: false,
    job: { kind: 'new', status: 'claimed', payoutCents: 2500, payoutStatus: 'pending', performedByAdminId: null },
  })
  const adminTake = admin.queries.find((q) => TAKE.test(q.sql))!
  assert.doesNotMatch(adminTake.sql, /jobs w where w\.worker_id/)
  assert.ok(adminTake.params.includes('pending'))
})

// ── Approval closes the block ───────────────────────────────────────────────

test('claimJobForApproval: the same statement closes the block as completed with who/when, guarded on status and other active jobs', async () => {
  const { db, queries } = recordingDb(when(/^with "approved"/, [[1]]))
  assert.deepEqual(await claimJobForApproval(db, 1, 3, NOW), { id: 1 })
  assert.equal(queries.length, 1)
  const { sql, params } = queries[0]
  assert.match(sql, /"closed" as \(update "blocks" set "status" = \$\d+, "completed_by_id" = \(select "worker_id" from "approved"\), "completed_at" = coalesce\(\(select "completed_at" from "approved"\), \$\d+::timestamptz\)/)
  assert.ok(params.includes('completed'))
  assert.match(sql, /"blocks"\."id" in \(select "block_id" from "approved"\)/)
  for (const s of APPROVAL_CLOSABLE_BLOCK_STATUSES) assert.ok(params.includes(s), s)
  assert.ok(!(APPROVAL_CLOSABLE_BLOCK_STATUSES as readonly string[]).includes('completed'), 'a completed block is not closable again')
  assert.match(sql, /not exists \(select 1 from jobs j where j\.block_id = "blocks"\."id" and j\.id <> \(select "id" from "approved"\)/)
  // The job is approved only if its block can be closed (same rule on the job UPDATE) …
  assert.match(sql, /exists \(select 1 from blocks cb where cb\.id = "jobs"\."block_id"\s+and cb\.status in \(\$\d+, \$\d+, \$\d+\)\s+and not exists \(select 1 from jobs o where o\.block_id = cb\.id and o\.id <> "jobs"\."id"/)
  // … and if the block UPDATE still matched nothing, the cast aborts the whole statement.
  assert.match(sql, /case when exists \(select 1 from "closed"\) then "approved"\."id"\s+else \(\$\d+ \|\| "approved"\."id"\)::int end/)
  assert.ok(params.some((p) => typeof p === 'string' && p.startsWith('approval aborted')))
})

test('claimJobForApproval: an aborted statement (block not closable) → null, never a thrown 500', async () => {
  const { db } = recordingDb((q) => {
    if (/^with "approved"/.test(q.sql)) throw new Error('invalid input syntax for type integer: "approval aborted: block not closable, job 1"')
    return undefined
  })
  assert.equal(await claimJobForApproval(db, 1, 3, NOW), null)
  const other = recordingDb((q) => {
    if (/^with "approved"/.test(q.sql)) throw new Error('connection reset')
    return undefined
  })
  // Any other failure still throws (the wrapper echoes params incl. the abort
  // marker — that must not be mistaken for an abort).
  await assert.rejects(claimJobForApproval(other.db, 1, 3, NOW), (e: { cause?: Error }) => e.cause?.message === 'connection reset')
})

test('claimJobForApproval: nothing approved (raced / denied / own job) → null, so the route answers 409 and no wallet credit runs', async () => {
  const { db } = recordingDb()
  assert.equal(await claimJobForApproval(db, 1, 3, NOW), null)
})

// ── Completion ──────────────────────────────────────────────────────────────

test('completeJob: a paid (re)submission clears review_status so it re-enters the review queue', async () => {
  const { db, queries } = recordingDb(when(/^update "jobs"/, [jobRow({ status: 'pending_review' })]))
  await completeJob(db, { id: 5, payoutAmount: '2500', payoutStatus: null }, { workerId: 7, isAdmin: false }, NOW)
  assert.match(queries[0].sql, /"review_status" = \$\d+/)
  assert.equal(queries.length, 1, 'a paid completion leaves the block in_progress until approval')
})

test('completeJob: a $0 test completion never closes the block — it goes back to the route’s eligible status IN THE SAME STATEMENT', async () => {
  const { db, queries } = recordingDb(
    when(/^with "done"/, [[5]]),
    when(/from "jobs" inner join "blocks"/, [['published', 'closed']]),
    when(/^select .* from "jobs" where "jobs"\."id"/, [jobRow({ status: 'completed', payoutAmount: '0' })]),
  )
  const row = await completeJob(db, { id: 5, payoutAmount: '0', payoutStatus: null }, { workerId: 7, isAdmin: false }, NOW)
  assert.equal(row?.status, 'completed')
  const stmt = queries.find((q) => /^with "done"/.test(q.sql))!
  assert.match(stmt.sql, /^with "done" as \(update "jobs" set "status" = \$1/)
  assert.doesNotMatch(stmt.sql, /"review_status" = \$/)
  assert.match(stmt.sql, /"reopened" as \(update "blocks" set "status" = \$\d+, "updated_at" = \$\d+ where \(\("blocks"\."id" in \(select "block_id" from "done"\)\) and \("blocks"\."status" = \$\d+\)/)
  assert.match(stmt.sql, /not exists \(select 1 from jobs j where j\.block_id = "blocks"\."id" and j\.id <> \(select "id" from "done"\)/)
  assert.ok(stmt.params.includes('funded'), 'published + closed route → funded')
  assert.ok(stmt.params.includes('in_progress'))
  assert.equal(queries.filter((q) => /^update "blocks"/.test(q.sql)).length, 0, 'no separate block statement')
})

// ── Deny → rework ───────────────────────────────────────────────────────────

test('denyJobForRework: pending_review → in_progress with the denial recorded and the run clock restarted', async () => {
  const { db, queries } = recordingDb(when(/^update "jobs"/, [jobRow({ status: 'in_progress' })]))
  const row = await denyJobForRework(db, 5, 3, 'Photos do not show the alley', NOW)
  assert.equal(row?.status, 'in_progress')
  const { sql, params } = queries[0]
  assert.match(sql, /^update "jobs" set "status" = \$1, "started_at" = \$2, "completed_at" = \$3/)
  assert.equal(params[0], 'in_progress')
  assert.equal(params[1], NOW)
  assert.equal(params[2], null)
  assert.ok(params.includes('denied'))
  assert.ok(params.includes('Photos do not show the alley'))
  assert.ok(params.includes(3))
  assert.match(flat(sql), /"jobs"\."status" = \$\d+ and "jobs"\."payout_status" is null or "jobs"\."payout_status" not in \$\d+, \$\d+ and "jobs"\."payout_stripe_transfer_id" is null/)
  assert.ok(params.includes('pending_review'))
})

test('denyJobForRework: nothing deniable (approved meanwhile / payout in flight) → null', async () => {
  const { db } = recordingDb()
  assert.equal(await denyJobForRework(db, 5, 3, 'x', NOW), null)
})

// ── Admin release ───────────────────────────────────────────────────────────

const releaseJob = (status: string, reviewStatus: string | null = null) =>
  when(/^select "id", "block_id", "worker_id", "status", "payout_amount", "review_status" from "jobs"/, [[5, 10, 7, status, '2500', reviewStatus]])

test('releaseJobToPool: a job denied under the old rule (pending_review + denied) can be released', async () => {
  const { db, queries } = recordingDb(
    releaseJob('pending_review', 'denied'),
    when(/from "blocks" left join "routes"/, [['published', 'open']]),
    when(/^with "released"/, [[5, 'available', 1, 0]]),
  )
  const r = await releaseJobToPool(db, 5, NOW)
  assert.equal(r.ok, true)
  const stmt = queries.find((q) => /^with "released"/.test(q.sql))!
  assert.ok(stmt.params.includes('pending_review'))
  assert.match(stmt.sql, /"jobs"\."review_status" = \$\d+/)
  assert.ok(stmt.params.includes('denied'))
})

test('releaseJobToPool: pending_review must be reviewed first; terminal jobs are refused; unknown → 404', async () => {
  const cases = [
    ['pending_review', /approve or deny/],
    ['completed', /Only a claimed or in-progress/],
    ['available', /Only a claimed or in-progress/],
  ] as const
  for (const [status, re] of cases) {
    const { db, queries } = recordingDb(releaseJob(status))
    const r = await releaseJobToPool(db, 5, NOW)
    assert.equal(r.ok, false)
    assert.equal(!r.ok && r.status, 409)
    assert.match(!r.ok ? r.message : '', re)
    assert.equal(writes(queries).length, 0)
  }
  const { db } = recordingDb()
  const r = await releaseJobToPool(db, 5, NOW)
  assert.equal(!r.ok && r.status, 404)
})

test('releaseJobToPool: a stalled / denied in-progress job goes back to the pool and its block back to the route status, in one statement', async () => {
  const { db, queries } = recordingDb(
    releaseJob('in_progress'),
    when(/from "blocks" left join "routes"/, [['published', 'open']]),
    when(/^with "released"/, [[5, 'available', 1, 1]]),
  )
  const r = await releaseJobToPool(db, 5, NOW)
  assert.deepEqual(r, { ok: true, job: { id: 5, status: 'available', blockId: 10 }, blockReopened: true, slotReopened: true })
  const stmt = queries.find((q) => /^with "released"/.test(q.sql))!
  // The canonical releaseJobPatch, guarded on the status + worker we read.
  assert.match(stmt.sql, /^with "released" as \(update "jobs" set "worker_id" = \$\d+, "status" = \$\d+/)
  assert.match(stmt.sql, /"review_status" = \$\d+, "denied_by_admin_id" = \$\d+, "deny_reason" = \$\d+/)
  assert.match(flat(stmt.sql), /"jobs"\."id" = \$\d+ and "jobs"\."status" = \$\d+ and "jobs"\."worker_id" = \$\d+/)
  assert.ok(stmt.params.includes('in_progress'))
  assert.ok(stmt.params.includes(7))
  // The block follows: in_progress → available (published + open), only if
  // the release happened and nobody else holds it.
  assert.match(stmt.sql, /"reopened" as \(update "blocks" set "status" = \$\d+/)
  assert.match(stmt.sql, /exists \(select 1 from "released"\)/)
  assert.match(stmt.sql, /not exists \(select 1 from jobs j where j\.block_id = "blocks"\."id" and j\.id <> \$\d+/)
  assert.ok(stmt.params.includes('available'))
  // The route-queue slot (and its completed batch) reopen in the SAME statement,
  // keyed on the released row — not a later statement a crash could skip.
  assert.match(stmt.sql, /"slot" as \(update "route_release_routes" set "status" = \$\d+/)
  assert.match(stmt.sql, /exists \(select 1 from "released" where "status" = 'available'\)/)
  assert.match(stmt.sql, /"batch" as \(update "route_release_batches" set "status" = \$\d+, "next_wave_at" = \$\d+/)
  assert.match(stmt.sql, /"route_release_batches"\."id" in \(select "batch_id" from "slot"\)/)
  assert.equal(queries.filter((q) => /^update "route_release_(routes|batches)"/.test(q.sql)).length, 0, 'no separate slot statement')
})

test('releaseJobToPool: the job moved between read and write → 409 (the one statement wrote nothing)', async () => {
  const { db, queries } = recordingDb(releaseJob('claimed'), when(/from "blocks" left join "routes"/, [['published', 'open']]))
  const r = await releaseJobToPool(db, 5, NOW)
  assert.equal(!r.ok && r.status, 409)
  assert.equal(queries.filter((q) => /^update /.test(q.sql)).length, 0)
})

test('declineClaimedJob: claimed → pool, block and slot in one statement, guarded on claimed + the holding worker', async () => {
  const { db, queries } = recordingDb(
    when(/from "blocks" left join "routes"/, [['published', 'open']]),
    when(/^with "released"/, [[5, 'available', 1, 0]]),
  )
  const r = await declineClaimedJob(db, { id: 5, blockId: 10, workerId: 7, payoutAmount: '2500' }, NOW)
  assert.deepEqual(r, { id: 5, status: 'available' })
  const stmt = queries.find((q) => /^with "released"/.test(q.sql))!
  assert.match(flat(stmt.sql), /"jobs"\."id" = \$\d+ and "jobs"\."status" = \$\d+ and "jobs"\."worker_id" = \$\d+/)
  assert.ok(stmt.params.includes('claimed'))
  assert.match(stmt.sql, /"reopened" as \(update "blocks"/)
  assert.match(stmt.sql, /"slot" as \(update "route_release_routes"/)
})

// ── Evidence floor ──────────────────────────────────────────────────────────

const CLAIMED = '2026-10-18T08:00:00.000Z'
const DENIED = '2026-10-18T11:00:00.000Z'

test('reworkSince / workFloor: the floor is the claim, or the denial when the job was sent back', () => {
  assert.equal(reworkSince({ reviewStatus: 'denied', reviewedAt: DENIED }), DENIED)
  assert.equal(reworkSince({ reviewStatus: 'approved', reviewedAt: DENIED }), null)
  assert.equal(reworkSince({ reviewStatus: null, reviewedAt: null }), null)
  assert.equal(workFloor({ claimedAt: CLAIMED, startedAt: null, reviewStatus: null, reviewedAt: null }), CLAIMED)
  assert.equal(workFloor({ claimedAt: CLAIMED, startedAt: null, reviewStatus: 'denied', reviewedAt: DENIED }), DENIED)
  assert.equal(workFloor({ claimedAt: null, startedAt: CLAIMED, reviewStatus: null, reviewedAt: null }), CLAIMED)
  // An old denial never lowers the floor below the claim.
  assert.equal(workFloor({ claimedAt: DENIED, startedAt: null, reviewStatus: 'denied', reviewedAt: CLAIMED }), DENIED)
})

// A photo URL on our store, as /api/spots/upload names it. The digits in the
// path are NOT trusted (a worker can type any); upload time comes from the store.
const HOST = 'abc.public.blob.vercel-storage.com'
const photo = (key: string, digits = 1700000000000) => `https://${HOST}/spots/7/${digits}-${key}-Xy12.jpg`
const NINE = '2026-10-18T09:00:00.000Z'
const TEN = '2026-10-18T10:00:00.000Z'
const NOON = '2026-10-18T12:00:00.000Z'
const entry = (url: string, capturedAt = NOON) => [{ url, capturedAt }]

/** A fake blob store: url → uploadedAt (ms); anything else does not exist. */
function store(uploads: Record<string, string>) {
  const asked: string[] = []
  const uploadedAtOf = async (url: string) => {
    asked.push(url)
    return uploads[url] ? Date.parse(uploads[url]) : null
  }
  return { asked, opts: { workerId: 7, uploadedAtOf, storeHost: HOST } }
}

test('hasEvidencePairSince: a different before and after photo that EXIST on our store, uploaded during this run', async () => {
  const s = store({ [photo('b')]: NINE, [photo('a')]: TEN })
  assert.equal(await hasEvidencePairSince(entry(photo('b')), entry(photo('a')), CLAIMED, s.opts), true)
  assert.equal(await hasEvidencePairSince(entry(photo('b')), null, CLAIMED, s.opts), false, 'no after photo')
  assert.equal(await hasEvidencePairSince(null, entry(photo('a')), CLAIMED, s.opts), false, 'no before photo')
  assert.equal(await hasEvidencePairSince(entry(photo('b')), entry(photo('b')), CLAIMED, s.opts), false, 'the same photo twice')
  assert.equal(await hasEvidencePairSince(entry(photo('b')), entry(photo('a')), null, s.opts), false, 'unknown floor')
})

test('hasEvidencePairSince: a forged URL (fresh-looking digits, no such object) never counts — Grok 2nd review', async () => {
  // Digits say "now"; the store says the object doesn't exist.
  const forgedB = photo('b', Date.parse(NOON) + 60_000)
  const forgedA = photo('a', Date.parse(NOON) + 120_000)
  const s = store({})
  assert.equal(await hasEvidencePairSince(entry(forgedB), entry(forgedA), CLAIMED, s.opts), false)
  assert.deepEqual(s.asked.sort(), [forgedA, forgedB].sort(), 'the store was asked, the path was not believed')
})

test('hasEvidencePairSince: re-sent pre-denial photos (fresh capturedAt) do not count; the store’s upload time does', async () => {
  const s = store({ [photo('b')]: NINE, [photo('a')]: TEN, [photo('c')]: '2026-10-18T11:30:00.000Z', [photo('d')]: '2026-10-18T11:40:00.000Z' })
  // Uploaded 09:00/10:00, denied 11:00, re-PATCHed at 12:00.
  assert.equal(await hasEvidencePairSince(entry(photo('b')), entry(photo('a')), DENIED, s.opts), false)
  assert.equal(await hasEvidencePairSince(entry(photo('c')), entry(photo('d')), DENIED, s.opts), true)
})

test('hasEvidencePairSince: a URL this job already held before the floor never counts, even if the store’s time passes', async () => {
  const reused = photo('b')
  const s = store({ [reused]: NOON, [photo('a')]: '2026-10-18T12:10:00.000Z', [photo('c')]: '2026-10-18T12:05:00.000Z' })
  const before = [{ url: reused, capturedAt: '2026-10-18T11:45:00.000Z' }, { url: reused, capturedAt: '2026-10-18T12:30:00.000Z' }]
  const after = entry(photo('a'), '2026-10-18T12:30:00.000Z')
  assert.equal(await hasEvidencePairSince(before, after, NOON, s.opts), false)
  const fresh = [...before, { url: photo('c'), capturedAt: '2026-10-18T12:31:00.000Z' }]
  assert.equal(await hasEvidencePairSince(fresh, after, NOON, s.opts), true)
})

test('hasEvidencePairSince: only this worker’s photos on OUR store; no store configured → nothing counts (fail closed)', async () => {
  const other = `https://${HOST}/spots/8/1700000000000-b-Xy12.jpg`
  const foreign = `https://evil.public.blob.vercel-storage.com/spots/7/1700000000000-a-Xy12.jpg`
  const s = store({ [other]: NINE, [foreign]: TEN, [photo('b')]: NINE, [photo('a')]: TEN })
  assert.equal(await hasEvidencePairSince(entry(other), entry(photo('a')), CLAIMED, s.opts), false, 'another worker’s photo')
  assert.equal(await hasEvidencePairSince(entry(photo('b')), entry(foreign), CLAIMED, s.opts), false, 'someone else’s store')
  const unconfigured = { ...s.opts, storeHost: null }
  assert.equal(await hasEvidencePairSince(entry(photo('b')), entry(photo('a')), CLAIMED, unconfigured), false)
})

// ── Canonical evidence URLs (parser/validator differential) ─────────────────

test('canonicalEvidenceUrl: lower-case scheme + host + pathname; query, fragment, port dropped', () => {
  const base = photo('b')
  assert.equal(canonicalEvidenceUrl(base), base)
  assert.equal(canonicalEvidenceUrl(`${base}?x=1`), base)
  assert.equal(canonicalEvidenceUrl(`${base}#a`), base)
  assert.equal(canonicalEvidenceUrl(`${base}?`), base)
  assert.equal(canonicalEvidenceUrl(base.replace('abc.public', 'ABC.Public').replace('https', 'HTTPS')), base)
  assert.equal(canonicalEvidenceUrl('not a url'), null)
})

test('isOwnEvidenceUrl: refuses any non-canonical spelling — ?query, #fragment, empty ?/#, upper-case host, percent-encoding', () => {
  const ok = `https://${HOST}/spots/7/1700000000000-photo_0-Xyz.jpg`
  assert.equal(isOwnEvidenceUrl(ok, 7, HOST), true)
  for (const bad of [
    `${ok}?x=1`,
    `${ok}#a`,
    `${ok}?`,
    `${ok}#`,
    ok.replace(HOST, HOST.toUpperCase()),
    ok.replace('abc.public', 'Abc.public'),
    ok.replace('photo_0', 'photo%5F0'),
    `https://${HOST}/spots/7/sub/1700000000000-photo_0-Xyz.jpg`,
  ]) {
    assert.equal(isOwnEvidenceUrl(bad, 7, HOST), false, bad)
  }
})

test('isOwnEvidenceUrl: fails closed without our store host — no wildcard *.public.blob.vercel-storage.com', () => {
  assert.equal(isOwnEvidenceUrl(`https://zzz.public.blob.vercel-storage.com/spots/7/a.jpg`, 7, null), false)
  assert.equal(isOwnEvidenceUrl(photo('b'), 7, null), false)
})

test('variants of one photo are the same photo at completion: before ≠ after and "already held" compare canonical URLs', async () => {
  const b = photo('b')
  const s = store({ [b]: NINE })
  for (const variant of [`${b}?x=1`, `${b}#a`, b.replace('abc.public', 'ABC.public')]) {
    assert.equal(await hasEvidencePairSince(entry(b), entry(variant), CLAIMED, s.opts), false, variant)
    assert.equal(await hasEvidencePairSince(entry(variant), entry(b), CLAIMED, s.opts), false, variant)
  }
  // Pre-denial evidence stored as a variant, re-sent canonically after the denial, is still "already held".
  const c = photo('c')
  const s2 = store({ [c]: NOON, [photo('d')]: NOON })
  const before = [{ url: `${c}?v=2`, capturedAt: '2026-10-18T11:30:00.000Z' }, { url: c, capturedAt: '2026-10-18T12:30:00.000Z' }]
  assert.equal(await hasEvidencePairSince(before, entry(photo('d')), NOON, s2.opts), false)
})

// ── 0055 conflicts outside takeBlockForJob; funded-only sponsor counts ──────

test('activePaidJobConflictMessage: 0055 violations (start / reassign) become clear 409 messages; anything else is null', () => {
  const v = (constraint: string) => ({ cause: { code: '23505', constraint } })
  assert.equal(activePaidJobConflictMessage(v('jobs_one_active_paid_job_per_worker')), 'That worker already has an active paid job')
  assert.equal(activePaidJobConflictMessage(v('jobs_one_active_paid_job_per_block')), 'That block already has an active paid job')
  assert.equal(activePaidJobConflictMessage(v('jobs_completion_scan_id_unique')), null)
  assert.equal(activePaidJobConflictMessage({ cause: { code: '23503' } }), null)
  assert.equal(activePaidJobConflictMessage(new Error('boom')), null)
})

test('startClaimedJob: an admin start that would give the admin a 2nd active paid job surfaces the 0055 violation (route → 409)', async () => {
  const { db } = recordingDb((q) => {
    if (!/^update "jobs"/.test(q.sql)) return undefined
    throw Object.assign(new Error('duplicate key'), { code: '23505', constraint: 'jobs_one_active_paid_job_per_worker' })
  })
  let caught: unknown = null
  try {
    await startClaimedJob(db, 5, { workerId: 99, isAdmin: true }, NOW)
  } catch (e) {
    caught = e
  }
  assert.equal(activePaidJobConflictMessage(caught), 'That worker already has an active paid job')
})

test('sponsor counts: a reservation (reserved_block_count set) is never a sponsored block — profile and map use the same predicate', async () => {
  const { db, queries } = recordingDb()
  await db.select({ n: count(fundedBlockRow()) }).from(transactions).where(isFundedBlockRow())
  const { sql } = queries[0]
  assert.match(sql, /count\(CASE WHEN \("transactions"\."block_id" IS NOT NULL AND "transactions"\."reserved_block_count" IS NULL\) THEN 1 END\)/)
  assert.match(sql, /where \("transactions"\."block_id" IS NOT NULL AND "transactions"\."reserved_block_count" IS NULL\)/)
})
