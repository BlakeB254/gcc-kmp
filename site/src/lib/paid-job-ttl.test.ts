import assert from 'node:assert/strict'
import test from 'node:test'

import { PgDialect } from 'drizzle-orm/pg-core'

import {
  PAID_CLAIM_RELEASE_HOURS,
  PAID_CLAIM_REMINDER_HOURS,
  PAID_IN_PROGRESS_REMINDER_HOURS,
  PAID_IN_PROGRESS_REVIEW_HOURS,
  paidJobAction,
  paidJobTtlCondition,
  sweepPaidJobs,
  type PaidJobCandidate,
  type PaidJobSweepDeps,
  type PushPayload,
} from './paid-job-ttl'

const T0 = Date.parse('2026-10-07T00:00:00.000Z')
const HOUR = 3_600_000
const at = (h: number) => new Date(T0 + h * HOUR).toISOString()

function job(overrides: Partial<PaidJobCandidate> = {}): PaidJobCandidate {
  return {
    id: 1,
    blockId: 10,
    workerId: 7,
    status: 'claimed',
    payoutAmount: '10000',
    claimedAt: at(0),
    startedAt: null,
    startReminderSentAt: null,
    progressReminderSentAt: null,
    stalledAt: null,
    ...overrides,
  }
}

test('policy constants are the agreed ones', () => {
  assert.deepEqual(
    [PAID_CLAIM_REMINDER_HOURS, PAID_CLAIM_RELEASE_HOURS, PAID_IN_PROGRESS_REMINDER_HOURS, PAID_IN_PROGRESS_REVIEW_HOURS],
    [4, 24, 12, 24],
  )
})

// ── Pure rules ──────────────────────────────────────────────────────────────

test('claimed paid job: nothing before 4h, reminder at 4h, release at 24h', () => {
  assert.equal(paidJobAction(job(), T0 + 3.9 * HOUR), null)
  assert.equal(paidJobAction(job(), T0 + 4 * HOUR)?.kind, 'remind-start')
  assert.equal(paidJobAction(job({ startReminderSentAt: at(4) }), T0 + 10 * HOUR), null, 'reminder already sent for this claim')
  assert.equal(paidJobAction(job({ startReminderSentAt: at(4) }), T0 + 24 * HOUR)?.kind, 'release')
})

test('claimed paid job: a reminder stamp from an EARLIER claim does not count', () => {
  const reclaimed = job({ claimedAt: at(30), startReminderSentAt: at(4) })
  assert.equal(paidJobAction(reclaimed, T0 + 34 * HOUR)?.kind, 'remind-start')
})

test('in-progress paid job: nothing before 12h, reminder at 12h, review-or-flag at 24h, never release', () => {
  const running = job({ status: 'in_progress', startedAt: at(1) })
  assert.equal(paidJobAction(running, T0 + 12.9 * HOUR), null)
  assert.equal(paidJobAction(running, T0 + 13 * HOUR)?.kind, 'remind-progress')
  assert.equal(paidJobAction({ ...running, progressReminderSentAt: at(13) }, T0 + 20 * HOUR), null)
  const day = paidJobAction(running, T0 + 25 * HOUR)
  assert.equal(day?.kind, 'review-or-flag')
  for (const h of [25, 48, 200, 2000]) {
    assert.notEqual(paidJobAction(running, T0 + h * HOUR)?.kind, 'release', `${h}h in progress must never release`)
  }
})

test('$0 jobs and unassigned jobs are not this policy\'s business', () => {
  assert.equal(paidJobAction(job({ payoutAmount: '0' }), T0 + 30 * HOUR), null)
  assert.equal(paidJobAction(job({ payoutAmount: null }), T0 + 30 * HOUR), null)
  assert.equal(paidJobAction(job({ workerId: null }), T0 + 30 * HOUR), null)
})

test('paidJobTtlCondition: paid only, claimed-not-started past 4h or in progress past 12h', () => {
  const { sql, params } = new PgDialect().sqlToQuery(paidJobTtlCondition())
  assert.match(sql, /coalesce\("jobs"\."payout_amount", 0\) > 0/)
  assert.match(sql, /"jobs"\."status" = 'claimed' and "jobs"\."started_at" is null/)
  assert.match(sql, /"jobs"\."status" = 'in_progress'/)
  assert.deepEqual(params, [4, 12])
})

// ── Sweep orchestration against a stateful fake DB ──────────────────────────

type Store = Map<number, PaidJobCandidate & { slotOpen?: boolean; reviewScanId?: number }>

function harness(rows: PaidJobCandidate[], scans: Record<number, number | null> = {}) {
  const store: Store = new Map(rows.map((r) => [r.id, { ...r }]))
  const pushes: Array<{ workerId: number; payload: PushPayload }> = []
  const adminNotes: PushPayload[] = []
  const tpl = (title: string) => (...args: unknown[]) => ({ title, body: args.join(' ') })
  const deps: PaidJobSweepDeps = {
    findCandidates: async () => [...store.values()].map((r) => ({ ...r })),
    claimStartReminder: async (j, now) => {
      const row = store.get(j.id)!
      if (row.startReminderSentAt && Date.parse(row.startReminderSentAt) >= Date.parse(row.claimedAt!)) return false
      row.startReminderSentAt = now
      return true
    },
    claimProgressReminder: async (j, now) => {
      const row = store.get(j.id)!
      if (row.progressReminderSentAt) return false
      row.progressReminderSentAt = now
      return true
    },
    releaseClaim: async (j) => {
      const row = store.get(j.id)!
      if (row.status !== 'claimed') return { released: false, slotReopened: false }
      Object.assign(row, { status: 'available', workerId: null, claimedAt: null, slotOpen: true })
      return { released: true, slotReopened: true }
    },
    findQualifyingScanId: async (j) => scans[j.id] ?? null,
    moveToReview: async (j, scanId) => {
      const row = store.get(j.id)!
      if (row.status !== 'in_progress') return false
      Object.assign(row, { status: 'pending_review', reviewScanId: scanId })
      return true
    },
    flagStalled: async (j, now) => {
      const row = store.get(j.id)!
      if (row.stalledAt) return false
      row.stalledAt = now
      return true
    },
    pushWorker: async (workerId, payload) => {
      pushes.push({ workerId, payload })
    },
    notifyAdmins: async (payload) => {
      adminNotes.push(payload)
    },
    templates: {
      startReminder: tpl('start'),
      progressReminder: tpl('progress'),
      autoReview: tpl('review'),
      stalled: tpl('stalled'),
    },
  }
  return { store, pushes, adminNotes, deps }
}

test('paid claimed job: pushed once at 4h (not again next tick), released at 24h with the queue slot reopened', async () => {
  const h = harness([job({ id: 1 })])

  const r4 = await sweepPaidJobs(h.deps, T0 + 4.25 * HOUR)
  assert.deepEqual(r4.startReminders, [1])
  const r5 = await sweepPaidJobs(h.deps, T0 + 4.5 * HOUR)
  assert.deepEqual(r5.startReminders, [], 'no second push')
  assert.equal(h.pushes.length, 1)
  assert.equal(h.pushes[0].workerId, 7)
  assert.equal(h.pushes[0].payload.title, 'start')

  const r24 = await sweepPaidJobs(h.deps, T0 + 24.25 * HOUR)
  assert.deepEqual(r24.released, [1])
  assert.deepEqual(r24.slotsReopened, [1])
  assert.equal(h.store.get(1)!.status, 'available')
  assert.equal(h.store.get(1)!.slotOpen, true)
})

test('paid in-progress job: pushed once at 12h; at 24h WITH a verified scan → pending_review + admins told', async () => {
  const h = harness([job({ id: 2, status: 'in_progress', startedAt: at(0) })], { 2: 900 })

  await sweepPaidJobs(h.deps, T0 + 12.25 * HOUR)
  await sweepPaidJobs(h.deps, T0 + 12.5 * HOUR)
  assert.equal(h.pushes.length, 1)
  assert.equal(h.pushes[0].payload.title, 'progress')

  const r = await sweepPaidJobs(h.deps, T0 + 24.25 * HOUR)
  assert.deepEqual(r.movedToReview, [2])
  assert.deepEqual(r.released, [])
  assert.equal(h.store.get(2)!.status, 'pending_review')
  assert.equal(h.store.get(2)!.reviewScanId, 900)
  assert.deepEqual(h.adminNotes.map((n) => n.title), ['review'])
})

test('paid in-progress job at 24h WITHOUT a verified scan → flagged once + admins told; never released', async () => {
  const h = harness([job({ id: 3, status: 'in_progress', startedAt: at(0) })])

  const r1 = await sweepPaidJobs(h.deps, T0 + 24.25 * HOUR)
  assert.deepEqual(r1.flagged, [3])
  const r2 = await sweepPaidJobs(h.deps, T0 + 48 * HOUR)
  assert.deepEqual(r2.flagged, [], 'flag + admin push happen once')
  const r3 = await sweepPaidJobs(h.deps, T0 + 500 * HOUR)
  assert.deepEqual(r3.released, [])
  assert.equal(h.store.get(3)!.status, 'in_progress')
  assert.equal(h.store.get(3)!.workerId, 7)
  assert.deepEqual(h.adminNotes.map((n) => n.title), ['stalled'])
})

test('a flagged job whose worker later submits a verified scan is moved to review on a later tick', async () => {
  const scans: Record<number, number | null> = {}
  const h = harness([job({ id: 4, status: 'in_progress', startedAt: at(0) })], scans)
  await sweepPaidJobs(h.deps, T0 + 24.25 * HOUR)
  assert.ok(h.store.get(4)!.stalledAt)
  scans[4] = 901
  const r = await sweepPaidJobs(h.deps, T0 + 30 * HOUR)
  assert.deepEqual(r.movedToReview, [4])
})

test('one failing job does not stop the sweep; the failure is reported', async () => {
  const h = harness([job({ id: 5 }), job({ id: 6 })])
  const original = h.deps.claimStartReminder
  h.deps.claimStartReminder = async (j, now) => {
    if (j.id === 5) throw new Error('db hiccup')
    return original(j, now)
  }
  const r = await sweepPaidJobs(h.deps, T0 + 5 * HOUR)
  assert.deepEqual(r.startReminders, [6])
  assert.deepEqual(r.failed, [{ id: 5, action: 'remind-start', error: 'db hiccup' }])
})
