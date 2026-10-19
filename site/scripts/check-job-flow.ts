/**
 * Integration check for the job/block state machine (lib/job-lifecycle.ts)
 * against a REAL Postgres with the production schema — never production.
 *
 *   cd site
 *   npm install --no-save pg            # node-postgres; not a project dependency
 *   GCC_FLOW_DATABASE_URL=postgres://gcc:gcc@127.0.0.1:56811/gcc_flow \
 *     npx tsx scripts/check-job-flow.ts
 *
 * Inserts its own fixture rows (ids 91000+), runs claim → start → complete →
 * deny → rework → resubmit → approve, release, a $0 completion, a guarded
 * sponsor payment and a claim race through the same lib functions the routes
 * call, asserts the rows after each step, and deletes the fixtures. Refuses
 * to run against anything that looks like Neon.
 */
import assert from 'node:assert/strict'
import { drizzle } from 'drizzle-orm/node-postgres'
// @ts-expect-error — installed with --no-save for this check only (no bundled types)
import pg from 'pg'
import * as schema from '../src/db/schema'
import {
  acceptOfferedJob,
  claimJobForApproval,
  claimWork,
  declineClaimedJob,
  releaseBlockClaim,
  completeJob,
  denyJobForRework,
  hasEvidencePairSince,
  takeBlockForJob,
  workFloor,
  releaseJobToPool,
  reopenBlockAfterJob,
  reworkSince,
  startClaimedJob,
  activePaidJobConflictMessage,
  type DbExecutor,
} from '../src/lib/job-lifecycle'
import { findQualifyingScanForJob } from '../src/lib/scan-completion'
import { recordPaymentOnce, sponsoredBlocksLine } from '../src/lib/stripe/sponsor-payment-events'
import { fundedBlockRow, isFundedBlockRow } from '../src/lib/sponsor-funded'
import { count, sql } from 'drizzle-orm'

const url = process.env.GCC_FLOW_DATABASE_URL
if (!url) throw new Error('Set GCC_FLOW_DATABASE_URL to a throwaway Postgres with the prod schema')
if (/neon\.tech|neon\.build/i.test(url)) throw new Error('Refusing to run against Neon')

const pool = new pg.Pool({ connectionString: url })
const db = drizzle({ client: pool, schema }) as unknown as DbExecutor
const q = async (text: string, params: unknown[] = []) => (await pool.query(text, params)).rows

const ID = 91000
const SA = ID, ADMIN = ID + 1, W1 = ID + 2, W2 = ID + 3, W3 = ID + 4, W4 = ID + 5, W5 = ID + 6
const R_OPEN = ID, R_ZERO = ID + 1
const B1 = ID, B2 = ID + 1, B3 = ID + 2, B4 = ID + 3, B5 = ID + 4, B6 = ID + 5
const SPONSOR = ID

const iso = (offsetMs = 0) => new Date(Date.now() + offsetMs).toISOString()

async function cleanup() {
  await q(`delete from transactions where sponsor_id = $1`, [SPONSOR])
  await q(`delete from sponsors where id = $1`, [SPONSOR])
  await q(`update jobs set completion_scan_id = null where block_id between $1 and $2`, [ID, ID + 99])
  await q(`delete from route_scans where route_id between $1 and $2`, [ID, ID + 99])
  await q(`delete from jobs where block_id between $1 and $2`, [ID, ID + 99])
  await q(`delete from blocks where id between $1 and $2`, [ID, ID + 99])
  await q(`delete from routes where id between $1 and $2`, [ID, ID + 99])
  await q(`delete from workers where id between $1 and $2`, [ID, ID + 99])
  await q(`delete from service_areas where id = $1`, [SA])
}

async function block(id: number) {
  const [row] = await q(`select status, completed_by_id, completed_at from blocks where id = $1`, [id])
  return row as { status: string; completed_by_id: number | null; completed_at: Date | null }
}
async function job(id: number) {
  const [row] = await q(`select * from jobs where id = $1`, [id])
  return row
}

async function main() {
  await cleanup()
  const geometry = JSON.stringify({ type: 'LineString', coordinates: [[-87.7, 41.86], [-87.699, 41.86]] })
  await q(`insert into service_areas (id, name, city, state, boundary, map_center) values ($1, 'flow', 'Chicago', 'IL', '{}', '{}')`, [SA])
  for (const [id, status] of [[ADMIN, 'approved'], [W1, 'approved'], [W2, 'approved'], [W3, 'pending'], [W4, 'approved'], [W5, 'approved']] as const) {
    await q(`insert into workers (id, name, email, phone, verification_status) values ($1, $2, $3, $4, $5)`, [id, `w${id}`, `w${id}@flow.test`, `+1555${id}`, status])
  }
  await q(`insert into routes (id, code, display_name, service_area_id, geometry, status, claim_status, payout_amount) values
    ($1, 'FLOW-OPEN', 'open', $3, $4, 'published', 'open', '2500'), ($2, 'FLOW-ZERO', 'zero', $3, $4, 'published', 'open', '0')`,
    [R_OPEN, R_ZERO, SA, geometry])
  await q(`insert into blocks (id, name, status, route_id) values
    ($1, 'b1', 'available', $7), ($2, 'b2', 'available', $7), ($3, 'b3', 'available', $8),
    ($4, 'b4', 'available', null), ($5, 'b5', 'available', $7), ($6, 'b6', 'funded', $7)`,
    [B1, B2, B3, B4, B5, B6, R_OPEN, R_ZERO])
  // B1 has an open job row, as a route release or a released job leaves it.
  const [{ id: J1 }] = await q(`insert into jobs (block_id, status, payout_amount) values ($1, 'available', '2500') returning id`, [B1])

  // ── Claim gates ─────────────────────────────────────────────────────────
  let r = await claimWork(db, { jobId: J1 }, W3, iso())
  assert.equal(!r.ok && r.status, 403, 'unverified worker refused')
  r = await claimWork(db, { blockId: B4 }, W2, iso())
  assert.match(!r.ok ? r.message : '', /not linked to a route/, 'routeless block refused')
  r = await claimWork(db, { blockId: B6 }, W2, iso())
  assert.match(!r.ok ? r.message : '', /funded but not yet available/, 'funded block refused')

  // ── App claim flips the block ───────────────────────────────────────────
  const claimedAt = iso(-10 * 60_000)
  r = await claimWork(db, { jobId: J1 }, W1, claimedAt)
  assert.ok(r.ok, 'W1 claims the open job')
  assert.equal((await block(B1)).status, 'in_progress', 'block follows the app claim')
  r = await claimWork(db, { blockId: B1 }, W2, iso())
  assert.match(!r.ok ? r.message : '', /already been claimed/, 'second worker refused')
  r = await claimWork(db, { blockId: B2 }, W1, iso())
  assert.match(!r.ok ? r.message : '', /already have an active job/, 'one active job per worker')

  assert.ok(await startClaimedJob(db, J1, { workerId: W1, isAdmin: false }, iso(-9 * 60_000)))

  // ── Complete on a verified scan ─────────────────────────────────────────
  const scanSql = `insert into route_scans (route_id, worker_id, start_lat, start_lng, status, phase, verified_coverage_ratio, created_at)
    values ($1, $2, 41.86, -87.7, 'completed', 'complete', 0.95, $3) returning id`
  const [{ id: S1 }] = await q(scanSql, [R_OPEN, W1, iso(-5 * 60_000)])
  let j = await job(J1)
  const forScan = (row: typeof j) => ({
    id: row.id, workerId: W1, claimedAt: row.claimed_at?.toISOString() ?? null, startedAt: row.started_at?.toISOString() ?? null,
    reworkSince: reworkSince({ reviewStatus: row.review_status, reviewedAt: row.reviewed_at?.toISOString() ?? null }),
  })
  assert.equal((await findQualifyingScanForJob(db, forScan(j), R_OPEN))?.id, S1)
  let done = await completeJob(db, { id: J1, payoutAmount: j.payout_amount, payoutStatus: j.payout_status }, { workerId: W1, isAdmin: false }, iso(-4 * 60_000), { completionScanId: S1 })
  assert.equal(done?.status, 'pending_review')

  // ── Deny → rework ───────────────────────────────────────────────────────
  const denied = await denyJobForRework(db, J1, ADMIN, 'Alley not covered', iso(-3 * 60_000))
  assert.equal(denied?.status, 'in_progress', 'denial sends the job back for rework')
  assert.equal(denied?.reviewStatus, 'denied')
  assert.equal((await block(B1)).status, 'in_progress', 'block stays with the reworking worker')
  assert.equal(await claimJobForApproval(db, J1, ADMIN, iso()), null, 'a denied job cannot be approved')
  j = await job(J1)
  assert.equal(await findQualifyingScanForJob(db, forScan(j), R_OPEN), null, 'the denied scan no longer counts')
  const [{ id: S2 }] = await q(scanSql, [R_OPEN, W1, iso(-2 * 60_000)])
  assert.equal((await findQualifyingScanForJob(db, forScan(j), R_OPEN))?.id, S2, 'a scan after the denial counts')
  done = await completeJob(db, { id: J1, payoutAmount: j.payout_amount, payoutStatus: j.payout_status }, { workerId: W1, isAdmin: false }, iso(-60_000), { completionScanId: S2 })
  assert.equal(done?.status, 'pending_review')
  assert.equal(done?.reviewStatus, null, 'the resubmission is back in the review queue')

  // ── Approve closes the block ────────────────────────────────────────────
  assert.deepEqual(await claimJobForApproval(db, J1, ADMIN, iso()), { id: J1 })
  const closed = await block(B1)
  assert.equal(closed.status, 'completed')
  assert.equal(closed.completed_by_id, W1)
  assert.ok(closed.completed_at)
  assert.equal((await job(J1)).status, 'completed')

  // ── Web claim + admin release ───────────────────────────────────────────
  r = await claimWork(db, { blockId: B2 }, W2, iso())
  assert.ok(r.ok)
  const J2 = r.ok ? r.job.id : -1
  assert.equal(r.ok && r.job.payoutAmount, '2500', 'payout from the route')
  await startClaimedJob(db, J2, { workerId: W2, isAdmin: false }, iso())
  const released = await releaseJobToPool(db, J2, iso())
  assert.ok(released.ok && released.blockReopened)
  assert.equal((await job(J2)).status, 'available')
  assert.equal((await job(J2)).worker_id, null)
  assert.equal((await block(B2)).status, 'available', 'published + open route → available again')
  r = await claimWork(db, { blockId: B2 }, W4, iso())
  assert.ok(r.ok && r.job.id === J2, 'the released job is reused, not duplicated')
  assert.equal(Number((await q(`select count(*) from jobs where block_id = $1`, [B2]))[0].count), 1)

  // A job denied under the old rule (pending_review + denied) is releasable.
  const [{ id: J3 }] = await q(
    `insert into jobs (block_id, worker_id, status, claimed_at, payout_amount, review_status, deny_reason, reviewed_at)
     values ($1, $2, 'pending_review', now(), '2500', 'denied', 'old', now()) returning id`, [B5, W5])
  await q(`update blocks set status = 'in_progress' where id = $1`, [B5])
  const legacy = await releaseJobToPool(db, J3, iso())
  assert.ok(legacy.ok)
  const legacyRow = await job(J3)
  assert.equal(legacyRow.status, 'available')
  assert.equal(legacyRow.review_status, null, 'the next worker does not inherit the denial')
  assert.equal((await block(B5)).status, 'available')

  // A worker declining a claim (PATCH /api/jobs/[id]/decline) puts the block back.
  r = await claimWork(db, { blockId: B5 }, W2, iso())
  assert.ok(r.ok)
  assert.equal((await block(B5)).status, 'in_progress')
  await q(`update jobs set worker_id = null, status = 'available', claimed_at = null where id = $1`, [r.ok ? r.job.id : -1])
  assert.ok(await reopenBlockAfterJob(db, B5, r.ok ? r.job.id : -1, iso()))
  assert.equal((await block(B5)).status, 'available')

  // ── $0 completion never closes the block ────────────────────────────────
  r = await claimWork(db, { blockId: B3 }, W5, iso())
  assert.ok(r.ok)
  const J4 = r.ok ? r.job.id : -1
  await startClaimedJob(db, J4, { workerId: W5, isAdmin: false }, iso())
  done = await completeJob(db, { id: J4, payoutAmount: '0', payoutStatus: null }, { workerId: ADMIN, isAdmin: true }, iso())
  assert.equal(done?.status, 'completed')
  assert.equal((await block(B3)).status, 'available', '$0 test run puts the block back')

  // ── Sponsor payment never overwrites a worked/cleaned block ─────────────
  await q(`insert into sponsors (id, name, email) values ($1, 'flow', 'flow@sponsor.test')`, [SPONSOR])
  const now = iso()
  const rows = [B1, B2, B6].map((blockId) => ({
    sponsorId: SPONSOR, blockId, stripeCheckoutSessionId: 'cs_flow', amount: '10000', status: 'completed' as const,
    allocationType: 'block' as const, completedAt: now,
  }))
  // B1 completed, B2 in_progress (W4 holds it), B6 funded: none may be overwritten.
  await q(`update blocks set status = 'available' where id = $1`, [B6]) // make B6 fundable
  const inserted = await recordPaymentOnce(db, {
    rows,
    fundBlocks: { status: 'funded', fundedById: SPONSOR, fundedAt: now, fundingCurrent: '10000', updatedAt: now },
    stats: { sponsorId: SPONSOR, contributedCents: 30000, blocksFunded: 3, at: now },
  })
  assert.equal(inserted.length, 3)
  assert.equal((await block(B1)).status, 'completed', 'completed block untouched')
  assert.equal((await block(B2)).status, 'in_progress', 'in-progress block untouched')
  assert.equal((await block(B6)).status, 'funded', 'available block funded')
  const tx = await q(`select block_id, reserved_block_count from transactions where sponsor_id = $1 order by block_id`, [SPONSOR])
  assert.deepEqual(tx.map((t) => [t.block_id, t.reserved_block_count]), [[B1, 1], [B2, 1], [B6, null]])
  const [sponsor] = await q(`select stats_blocks_funded from sponsors where id = $1`, [SPONSOR])
  assert.equal(Number(sponsor.stats_blocks_funded), 1, 'only the funded block counts')

  // Public totals count only rows that FUNDED a block (Grok 2nd review): the
  // two reservations keep their block_id but must not count as sponsored.
  const [counts] = await db
    .select({
      funded: count(fundedBlockRow()),
      naive: count(sql`CASE WHEN ${schema.transactions.blockId} IS NOT NULL THEN 1 END`),
    })
    .from(schema.transactions)
    .where(sql`${schema.transactions.sponsorId} = ${SPONSOR}`)
  assert.equal(Number(counts.funded), 1, 'public totals: one funded block')
  assert.equal(Number(counts.naive), 3, '(the old count would have said 3)')
  // The map's per-block sponsor count (affected-areas) uses the WHERE form:
  // only the funded block appears, and with one sponsor.
  const perBlock = await db
    .select({ blockId: schema.transactions.blockId, sponsorCount: count() })
    .from(schema.transactions)
    .where(sql`${schema.transactions.sponsorId} = ${SPONSOR} and ${schema.transactions.status} = 'completed' and ${isFundedBlockRow()}`)
    .groupBy(schema.transactions.blockId)
  assert.deepEqual(perBlock.map((r: { blockId: number; sponsorCount: number }) => [r.blockId, Number(r.sponsorCount)]), [[B6, 1]], 'map: reserved blocks show no sponsor')
  assert.equal(
    await sponsoredBlocksLine(db, inserted),
    'b6; 2 blocks reserved for their next cleaning: b1, b2',
    'the email thanks the sponsor for b6 and says b1/b2 are reserved',
  )

  // ── Two workers race for one block ──────────────────────────────────────
  await q(`update blocks set status = 'available' where id = $1`, [B3])
  await q(`update jobs set status = 'disputed' where block_id = $1`, [B3])
  const [a, b] = await Promise.all([claimWork(db, { blockId: B3 }, W1, iso()), claimWork(db, { blockId: B3 }, ADMIN, iso())])
  assert.equal([a, b].filter((x) => x.ok).length, 1, 'exactly one claim wins')
  assert.equal(Number((await q(`select count(*) from jobs where block_id = $1 and status = 'claimed'`, [B3]))[0].count), 1)

  // ── The database refuses a second active PAID job on a block (0055) ─────
  const W6 = ID + 7, B7 = ID + 6, B8 = ID + 7
  await q(`insert into workers (id, name, email, phone, verification_status) values ($1, 'w6', 'w6@flow.test', '+1555w6', 'approved')`, [W6])
  await q(`insert into blocks (id, name, status, route_id) values ($1, 'b7', 'available', $3), ($2, 'b8', 'available', $3)`, [B7, B8, R_OPEN])
  await q(`insert into jobs (block_id, worker_id, status, claimed_at, payout_amount) values ($1, $2, 'in_progress', now(), '2500')`, [B7, W1])
  await assert.rejects(
    q(`insert into jobs (block_id, worker_id, status, claimed_at, payout_amount) values ($1, $2, 'claimed', now(), '2500')`, [B7, W6]),
    (e: { code?: string; constraint?: string }) => e.code === '23505' && e.constraint === 'jobs_one_active_paid_job_per_block',
    'raw SQL cannot put two active paid jobs on one block',
  )
  // $0 test runs are exempt (several testers may run one route).
  await q(`insert into jobs (block_id, worker_id, status, claimed_at, payout_amount) values ($1, $2, 'claimed', now(), '0')`, [B7, W6])
  // Admin assignment (ensureJobForRouteWorker → takeBlockForJob, no worker
  // rule) on a held block → block_held (409), nothing written.
  const held = await takeBlockForJob(db, {
    blockId: B7,
    workerId: W6,
    now: iso(),
    fromStatuses: ['available', 'funded', 'completed', 'in_progress'],
    requireWorkerFree: false,
    job: { kind: 'new', status: 'claimed', payoutCents: 2500, payoutStatus: 'pending' },
  })
  assert.deepEqual(held, { ok: false, reason: 'block_held' })
  assert.equal(Number((await q(`select count(*) from jobs where block_id = $1 and payout_amount > 0 and status in ('claimed','in_progress','pending_review')`, [B7]))[0].count), 1)
  await q(`delete from jobs where block_id = $1`, [B7])

  // Admin assignment of a second PAID route to a busy worker → worker_busy.
  await q(`insert into jobs (block_id, worker_id, status, claimed_at, payout_amount) values ($1, $2, 'in_progress', now(), '2500')`, [B8, W6])
  await assert.rejects(
    q(`insert into jobs (block_id, worker_id, status, claimed_at, payout_amount) values ($1, $2, 'claimed', now(), '2500')`, [B7, W6]),
    (e: { code?: string; constraint?: string }) => e.code === '23505' && e.constraint === 'jobs_one_active_paid_job_per_worker',
    'raw SQL cannot give one worker two active paid jobs',
  )
  const busyWorker = await takeBlockForJob(db, {
    blockId: B7,
    workerId: W6,
    now: iso(),
    fromStatuses: ['available', 'funded', 'completed', 'in_progress'],
    requireWorkerFree: false,
    job: { kind: 'new', status: 'claimed', payoutCents: 2500, payoutStatus: 'pending' },
  })
  assert.deepEqual(busyWorker, { ok: false, reason: 'worker_busy' })
  assert.equal((await block(B7)).status, 'available', 'the refused take left the block alone (statement rolled back)')
  await q(`delete from jobs where worker_id = $1`, [W6])

  // ── One worker, two blocks, concurrent claims: never two active jobs ────
  // The per-worker index is the guarantee: under neon-http both statements
  // may see "free", but only one can commit its row.
  const outcomes: Record<number, number> = { 0: 0, 1: 0, 2: 0 }
  for (let i = 0; i < 25; i++) {
    const results = await Promise.all([
      claimWork(db, { blockId: B7 }, W6, iso()),
      claimWork(db, { blockId: B8 }, W6, iso()),
    ])
    const wins = results.filter((x) => x.ok).length
    outcomes[wins]++
    const [{ count: active }] = await q(`select count(*)::int as count from jobs where worker_id = $1 and status in ('claimed','in_progress','pending_review')`, [W6])
    assert.ok(active <= 1, `iteration ${i}: worker holds ${active} active jobs`)
    assert.equal(active, wins, `iteration ${i}: every winner has its job; the loser wrote nothing`)
    const orphans = await q(
      `select b.id from blocks b where b.id in ($1, $2) and b.status = 'in_progress'
         and not exists (select 1 from jobs j where j.block_id = b.id and j.status in ('claimed','in_progress','pending_review'))`,
      [B7, B8],
    )
    assert.equal(orphans.length, 0, `iteration ${i}: no block left in_progress without a job (one-statement take)`)
    await q(`delete from jobs where worker_id = $1`, [W6])
    await q(`update blocks set status = 'available' where id in ($1, $2)`, [B7, B8])
  }
  console.log(`same-worker race, 25 rounds: ${outcomes[1]} with one winner, ${outcomes[0]} with none, ${outcomes[2]} with two`)
  assert.equal(outcomes[2], 0, 'never two winners')

  // ── Routeless evidence gate: pre-denial photos re-sent after the denial ─
  // Upload times come from the blob store (head().uploadedAt); here a fake
  // store answers, keyed by canonical URL. Digits in the path mean nothing.
  const photoAt = (key: string) => `https://abc.public.blob.vercel-storage.com/spots/${W5}/1700000000000-${key}-Ab12.jpg`
  const claimMs = Date.now() - 60 * 60_000
  const deniedMs = Date.now() - 30 * 60_000
  const oldB = photoAt('b'), oldA = photoAt('a'), newB = photoAt('nb'), newA = photoAt('na')
  const uploads: Record<string, number> = {
    [oldB]: claimMs + 60_000,
    [oldA]: claimMs + 120_000,
    [newB]: Date.now() - 10 * 60_000,
    [newA]: Date.now() - 5 * 60_000,
  }
  const evidenceOpts = {
    workerId: W5,
    storeHost: 'abc.public.blob.vercel-storage.com',
    uploadedAtOf: async (url: string) => uploads[url] ?? null,
  }
  const at = (ms: number) => new Date(ms).toISOString()
  // First submission's photos, then the same photos PATCHed again after the
  // denial (fresh capturedAt), the second time as ?query / host-case variants.
  const resent = (url: string, variant: string) => JSON.stringify([
    { url, capturedAt: at(claimMs + 180_000) },
    { url: variant, capturedAt: at(Date.now()) },
  ])
  const [{ id: J5 }] = await q(
    `insert into jobs (block_id, worker_id, status, claimed_at, payout_amount, review_status, reviewed_at, before_photos, after_photos)
     values ($1, $2, 'in_progress', $3, '2500', 'denied', $4, $5::jsonb, $6::jsonb) returning id`,
    [B4, W5, at(claimMs), at(deniedMs), resent(oldB, `${oldB}?x=1`), resent(oldA, oldA.replace('abc.public', 'ABC.public'))],
  )
  let row5 = await job(J5)
  const floor5 = workFloor({
    claimedAt: row5.claimed_at.toISOString(), startedAt: null, reviewStatus: row5.review_status, reviewedAt: row5.reviewed_at.toISOString(),
  })
  assert.equal(await hasEvidencePairSince(row5.before_photos, row5.after_photos, floor5, evidenceOpts), false, 're-sent pre-denial photos do not count')
  // A forged URL (no such object on the store) never counts, whatever its path says.
  const forged = `https://abc.public.blob.vercel-storage.com/spots/${W5}/${Date.now() + 60_000}-forged-Zz99.jpg`
  await q(
    `update jobs set before_photos = before_photos || $2::jsonb, after_photos = after_photos || $3::jsonb where id = $1`,
    [J5, JSON.stringify([{ url: forged, capturedAt: at(Date.now()) }]), JSON.stringify([{ url: `${newB}#a`, capturedAt: at(Date.now()) }])],
  )
  row5 = await job(J5)
  assert.equal(await hasEvidencePairSince(row5.before_photos, row5.after_photos, floor5, evidenceOpts), false, 'forged before photo')
  await q(`update jobs set before_photos = before_photos || $2::jsonb where id = $1`, [J5, JSON.stringify([{ url: newB, capturedAt: at(Date.now()) }])])
  row5 = await job(J5)
  assert.equal(await hasEvidencePairSince(row5.before_photos, row5.after_photos, floor5, evidenceOpts), false, 'the same photo as before and after')
  await q(`update jobs set after_photos = after_photos || $2::jsonb where id = $1`, [J5, JSON.stringify([{ url: newA, capturedAt: at(Date.now()) }])])
  row5 = await job(J5)
  assert.equal(await hasEvidencePairSince(row5.before_photos, row5.after_photos, floor5, evidenceOpts), true, 'new, different, real photos after the denial count')
  await q(`delete from jobs where id = $1`, [J5])

  // ── Grok #2: a block someone holds is never reopened or double-claimed ──
  const W7 = ID + 8, W8 = ID + 9, W9 = ID + 10, B9 = ID + 8
  for (const w of [W7, W8, W9]) {
    await q(`insert into workers (id, name, email, phone, verification_status) values ($1, $2, $3, $4, 'approved')`, [w, `w${w}`, `w${w}@flow.test`, `+1555${w}`])
  }
  await q(`insert into blocks (id, name, status, route_id) values ($1, 'b9', 'available', $2)`, [B9, R_OPEN])
  const [{ id: J9 }] = await q(`insert into jobs (block_id, status, payout_amount) values ($1, 'available', '2500') returning id`, [B9])
  // Offer accept and a web claim race for the same block's open job.
  const [offer, web] = await Promise.all([
    acceptOfferedJob(db, { jobId: J9, blockId: B9, workerId: W7, now: iso() }),
    claimWork(db, { blockId: B9 }, W8, iso()),
  ])
  assert.equal([offer.ok, web.ok].filter(Boolean).length, 1, 'exactly one of them takes the block')
  assert.equal((await block(B9)).status, 'in_progress')
  await releaseBlockClaim(db, B9, iso())
  assert.equal((await block(B9)).status, 'in_progress', 'the compensation helper never reopens a held block')
  r = await claimWork(db, { blockId: B9 }, W9, iso())
  assert.equal(r.ok, false, 'nobody else can claim the held block')
  assert.equal(Number((await q(`select count(*) from jobs where block_id = $1 and status in ('claimed','in_progress','pending_review')`, [B9]))[0].count), 1, 'still one active job')
  // Offer accept on a route-queue block (funded) flips it in the same statement.
  const B10 = ID + 9
  await q(`insert into blocks (id, name, status, route_id) values ($1, 'b10', 'funded', $2)`, [B10, R_OPEN])
  const [{ id: J10 }] = await q(`insert into jobs (block_id, status, payout_amount) values ($1, 'available', '2500') returning id`, [B10])
  assert.ok((await acceptOfferedJob(db, { jobId: J10, blockId: B10, workerId: W9, now: iso() })).ok)
  assert.equal((await block(B10)).status, 'in_progress', 'offer accept takes the block')

  // ── Grok #3: a reused open job is claimed at the ROUTE payout ───────────
  const W10 = ID + 11, B11 = ID + 10
  await q(`insert into workers (id, name, email, phone, verification_status) values ($1, 'w10', 'w10@flow.test', '+1555w10', 'approved')`, [W10])
  await q(`insert into blocks (id, name, status, route_id) values ($1, 'b11', 'available', $2)`, [B11, R_OPEN])
  const [{ id: J11 }] = await q(`insert into jobs (block_id, status, payout_amount) values ($1, 'available', '0') returning id`, [B11])
  r = await claimWork(db, { blockId: B11 }, W10, iso())
  assert.ok(r.ok && r.job.id === J11, 'the open job is reused')
  assert.equal(Number((await job(J11)).payout_amount), 2500, 'stale $0 replaced by the route payout')

  // ── Start / reassign onto a busy worker: 0055 → a clear 409 ─────────────
  {
    const WB = ID + 20, WC = ID + 21, BS1 = ID + 20, BS2 = ID + 21
    for (const w of [WB, WC]) {
      await q(`insert into workers (id, name, email, phone, verification_status) values ($1, $2, $3, $4, 'approved')`, [w, `w${w}`, `w${w}@flow.test`, `+1555${w}`])
    }
    await q(`insert into blocks (id, name, status, route_id) values ($1, 'bs1', 'in_progress', $3), ($2, 'bs2', 'in_progress', $3)`, [BS1, BS2, R_OPEN])
    // WB already works BS1; BS2's paid job is claimed with no worker (admin flow).
    await q(`insert into jobs (block_id, worker_id, status, claimed_at, payout_amount) values ($1, $2, 'in_progress', now(), '2500')`, [BS1, WB])
    const [{ id: JS2 }] = await q(`insert into jobs (block_id, status, claimed_at, payout_amount) values ($1, 'claimed', now(), '2500') returning id`, [BS2])
    // An admin (WB) starting it becomes its worker via COALESCE → per-worker index.
    let startErr: unknown = null
    try { await startClaimedJob(db, JS2, { workerId: WB, isAdmin: true }, iso()) } catch (e) { startErr = e }
    assert.equal(activePaidJobConflictMessage(startErr), 'That worker already has an active paid job', 'start → 409 message')
    assert.equal((await job(JS2)).status, 'claimed', 'the start rolled back')
    // Reassigning BS2's job to the busy WB (the reassign route's UPDATE).
    await q(`update jobs set worker_id = $2 where id = $1`, [JS2, WC])
    let reassignErr: unknown = null
    try {
      await db.update(schema.jobs).set({ workerId: WB }).where(sql`${schema.jobs.id} = ${JS2} and ${schema.jobs.workerId} = ${WC}`).returning({ id: schema.jobs.id })
    } catch (e) { reassignErr = e }
    assert.equal(activePaidJobConflictMessage(reassignErr), 'That worker already has an active paid job', 'reassign → 409 message')
    assert.equal(Number((await job(JS2)).worker_id), WC)
    // A second active paid job on a held block → the block message.
    let blockErr: unknown = null
    try {
      await db.update(schema.jobs).set({ blockId: BS1 }).where(sql`${schema.jobs.id} = ${JS2}`)
    } catch (e) { blockErr = e }
    assert.equal(activePaidJobConflictMessage(blockErr), 'That block already has an active paid job')
    assert.equal(activePaidJobConflictMessage(new Error('other')), null)
    await q(`delete from jobs where block_id in ($1, $2)`, [BS1, BS2])
  }

  // ── Approval is all-or-nothing: block not closable → nothing approved ───
  const W11 = ID + 12, W12 = ID + 13, B12 = ID + 11
  for (const w of [W11, W12]) {
    await q(`insert into workers (id, name, email, phone, verification_status) values ($1, $2, $3, $4, 'approved')`, [w, `w${w}`, `w${w}@flow.test`, `+1555${w}`])
  }
  await q(`insert into blocks (id, name, status, route_id) values ($1, 'b12', 'in_progress', $2)`, [B12, R_OPEN])
  const [{ id: J12 }] = await q(
    `insert into jobs (block_id, worker_id, status, claimed_at, completed_at, payout_amount) values ($1, $2, 'pending_review', now(), now(), '2500') returning id`,
    [B12, W11],
  )
  // Index 0055 forbids a second active PAID job here; a $0 test run on the
  // same block is still "another active job" for approval.
  await q(`insert into jobs (block_id, worker_id, status, claimed_at, payout_amount) values ($1, $2, 'in_progress', now(), '0')`, [B12, W12])
  assert.equal(await claimJobForApproval(db, J12, ADMIN, iso()), null, 'another worker still on the block → not approved')
  assert.equal((await job(J12)).status, 'pending_review', 'the job is untouched (no wallet credit follows)')
  assert.equal((await block(B12)).status, 'in_progress')
  await q(`update jobs set status = 'completed' where block_id = $1 and worker_id = $2`, [B12, W12])
  assert.deepEqual(await claimJobForApproval(db, J12, ADMIN, iso()), { id: J12 }, 'once the block is free it approves')
  assert.equal((await block(B12)).status, 'completed')
  const [{ id: J13 }] = await q(
    `insert into jobs (block_id, worker_id, status, claimed_at, completed_at, payout_amount) values ($1, $2, 'pending_review', now(), now(), '2500') returning id`,
    [B12, W12],
  )
  assert.equal(await claimJobForApproval(db, J13, ADMIN, iso()), null, 'a completed block is not closable again')
  assert.equal((await job(J13)).status, 'pending_review')

  // ── Decline: job, block and route-queue slot in one statement ───────────
  const W13 = ID + 14, B13 = ID + 12
  await q(`insert into workers (id, name, email, phone, verification_status) values ($1, 'w13', 'w13@flow.test', '+1555w13', 'approved')`, [W13])
  await q(`insert into blocks (id, name, status, route_id) values ($1, 'b13', 'funded', $2)`, [B13, R_OPEN])
  const [{ id: J14 }] = await q(`insert into jobs (block_id, status, payout_amount) values ($1, 'available', '2500') returning id`, [B13])
  const [{ id: BATCH }] = await q(
    `insert into route_release_batches (service_area_id, name, community_name, eligible_zip_codes, release_at, status)
     values ($1, 'flow', 'flow', '[]', now(), 'completed') returning id`, [SA])
  await q(`insert into route_release_routes (batch_id, route_id, job_id, status) values ($1, $2, $3, 'open')`, [BATCH, R_OPEN, J14])
  assert.ok((await acceptOfferedJob(db, { jobId: J14, blockId: B13, workerId: W13, now: iso() })).ok)
  await q(`update route_release_routes set status = 'claimed' where job_id = $1`, [J14])
  const declined = await declineClaimedJob(db, { id: J14, blockId: B13, workerId: W13, payoutAmount: '2500' }, iso())
  assert.equal(declined?.status, 'available')
  assert.equal((await block(B13)).status, 'available', 'block back to its route status (published + open)')
  assert.equal((await q(`select status from route_release_routes where job_id = $1`, [J14]))[0].status, 'open', 'slot reopened')
  assert.equal((await q(`select status from route_release_batches where id = $1`, [BATCH]))[0].status, 'active', 'completed batch re-activated')
  await q(`delete from route_release_routes where batch_id = $1`, [BATCH])
  await q(`delete from route_release_batches where id = $1`, [BATCH])

  console.log('job flow check: all assertions passed')
}

main()
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
  .finally(async () => {
    await cleanup().catch((e) => console.error('cleanup failed', e))
    await pool.end()
  })
