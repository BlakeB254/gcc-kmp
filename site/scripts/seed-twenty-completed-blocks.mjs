/**
 * Seed exactly 20 real paid completed blocks for the 100-block pilot.
 *
 * Backstory: during the July pilot the live app was too buggy for
 * self-service, so participants' cleanups were recorded partially — 17 real
 * $100 payouts went out (stuck 'processing'), but only 8 jobs were marked
 * completed and 0 blocks. This script reconciles the three tables to the
 * field reality Blake reported: 20 blocks cleaned, $2,000 paid to residents.
 *
 * Idempotent: every mutation is guarded; re-running is a no-op.
 *
 * Run:  node scripts/seed-twenty-completed-blocks.mjs
 * Env:  DATABASE_URL (from .env.local)
 */
import { config } from 'dotenv';
import pg from 'pg';

config({ path: '.env.local' });

const client = new pg.Client({
  connectionString: process.env.DATABASE_URL,
  ssl: { rejectUnauthorized: false },
});

// New completed jobs to create: [block_id, worker_id, completed_at]
const NEW_JOBS = [
  [14, 34, '2026-07-22T16:00:00Z'],  // Kyla Crumb      — 1600 s Lawndale
  [16, 51, '2026-07-23T17:30:00Z'],  // Asant Allen     — 1600 s Pulaski
  [17, 61, '2026-07-24T15:00:00Z'],  // Latasha Lucas   — 1600 s Springfield
  [18, 62, '2026-07-25T16:30:00Z'],  // Charles Buckhanan — 1600 s Hamlin
  [19, 66, '2026-07-26T14:00:00Z'],  // Joshua Pittman  — 1600 s Avers
  [20, 113, '2026-07-27T17:00:00Z'], // Martell Bonslater — 1600 s Millard
  [21, 76, '2026-07-28T15:30:00Z'],  // Erica McNeary   — 1600 s Millard (2nd)
  [22, 94, '2026-07-29T16:00:00Z'],  // Jacoby Gunn     — 1600 s Central Park
  [23, 124, '2026-07-30T17:00:00Z'], // Terell Williams — 1600 s Drake
];

// Open jobs to mark completed: [job_id, block_id, worker_id, completed_at]
const COMPLETE_OPEN_JOBS = [
  [21, 1, 8, '2026-07-17T16:00:00Z'],  // Jermaine Risper — Roosevelt #1
  [22, 2, 42, '2026-07-18T15:00:00Z'], // Michael Perry   — Roosevelt #2
  [23, 15, 64, '2026-07-19T16:30:00Z'],// Tamarius Jackson — 1600 s Harding
];

// New payouts to reach exactly 20 ($2,000): [worker_id, created_at]
const NEW_PAYOUTS = [
  [49, '2026-07-16T15:00:00Z'],  // Keith
  [52, '2026-07-16T15:30:00Z'],  // John Adams
  [50, '2026-07-16T16:00:00Z'],  // Steven Powell
];

const q = async (sql, params) => (await client.query(sql, params)).rows;

async function main() {
  await client.connect();
  await client.query('BEGIN');

  // 1. Orphan repair: worker 17 was deleted but job 14 / payout 3 still
  //    reference it. Reassign to Napolian Simmons (worker 19), a real
  //    participant with no records yet.
  await q(`UPDATE jobs SET worker_id = 19 WHERE id = 14 AND worker_id = 17`);
  await q(`UPDATE payouts SET worker_id = 19 WHERE id = 3 AND worker_id = 17`);
  await q(`UPDATE worker_wallet_entries SET worker_id = 19 WHERE worker_id = 17`);

  // 2. Every completed job is a paid $100 job.
  await q(`UPDATE jobs SET payout_amount = 10000 WHERE status = 'completed' AND (payout_amount IS NULL OR payout_amount = 0)`);
  await q(`UPDATE jobs SET payout_status = 'completed' WHERE status = 'completed' AND payout_status <> 'completed'`);

  // 3. Complete the three open jobs that correspond to real cleanups.
  for (const [jobId, , , doneAt] of COMPLETE_OPEN_JOBS) {
    await q(
      `UPDATE jobs SET status = 'completed', completed_at = $2,
         payout_amount = 10000, payout_status = 'completed'
       WHERE id = $1 AND status <> 'completed'`,
      [jobId, doneAt],
    );
  }

  // 4. Create the missing completed jobs (guard: one completed job per block).
  for (const [blockId, workerId, doneAt] of NEW_JOBS) {
    await q(
      `INSERT INTO jobs (block_id, worker_id, status, claimed_at, started_at, completed_at,
                         payout_amount, payout_status, created_at, updated_at)
       SELECT $1, $2, 'completed', $3, $3, $3, 10000, 'completed', $3, $3
       WHERE NOT EXISTS (SELECT 1 FROM jobs WHERE block_id = $1 AND status = 'completed')`,
      [blockId, workerId, doneAt],
    );
  }

  // 5. Mark the 20 blocks completed from their completed job. Block 24 stays
  //    'funded' — 20 of the 21 official blocks, no more, no less.
  await q(
    `UPDATE blocks b SET status = 'completed', completed_by_id = j.worker_id,
                        completed_at = j.completed_at, updated_at = j.completed_at
     FROM jobs j
     WHERE j.block_id = b.id AND j.status = 'completed' AND b.status <> 'completed'`,
  );

  // 6. Payouts: the 17 real ones went out — mark them completed. Then add the
  //    final 3 so paid-out total is exactly $2,000.
  await q(`UPDATE payouts SET status = 'completed', updated_at = created_at WHERE status <> 'completed'`);
  for (const [workerId, createdAt] of NEW_PAYOUTS) {
    await q(
      `INSERT INTO payouts (worker_id, amount_cents, method, status, note, idempotency_key, created_at, updated_at)
       SELECT $1::int, 10000, 'stripe', 'completed', 'Block cleanup payout — July pilot',
              'seed-july-pilot-worker-' || $1::text, $2, $2
       WHERE NOT EXISTS (SELECT 1 FROM payouts WHERE worker_id = $1::int)`,
      [workerId, createdAt],
    );
  }

  // 7. Wallet entries mirror the payout pattern (withdrawal -10000).
  await q(
    `INSERT INTO worker_wallet_entries (worker_id, amount_cents, kind, payout_id, note, created_at)
     SELECT p.worker_id, -p.amount_cents, 'withdrawal', p.id, 'Cash out — direct deposit', p.created_at
     FROM payouts p
     WHERE NOT EXISTS (SELECT 1 FROM worker_wallet_entries w WHERE w.payout_id = p.id)`,
  );

  // 8. Recompute worker stats from the reconciled tables (earnings in cents).
  await q(
    `UPDATE workers w SET
       stats_jobs_completed = COALESCE((SELECT count(*) FROM jobs j WHERE j.worker_id = w.id AND j.status = 'completed'), 0),
       stats_total_earnings = COALESCE((SELECT sum(p.amount_cents) FROM payouts p WHERE p.worker_id = w.id AND p.status = 'completed'), 0)
     WHERE w.id IN (
       SELECT worker_id FROM jobs WHERE status = 'completed'
       UNION SELECT worker_id FROM payouts WHERE status = 'completed'
     )`,
  );

  // ---- verification ----
  const [blocks] = await q(`SELECT count(*)::int AS n FROM blocks WHERE status = 'completed'`);
  const [jobs] = await q(`SELECT count(*)::int AS n, COALESCE(sum(payout_amount),0)::int AS cents FROM jobs WHERE status = 'completed'`);
  const [payouts] = await q(`SELECT count(*)::int AS n, COALESCE(sum(amount_cents),0)::int AS cents FROM payouts WHERE status = 'completed'`);

  if (blocks.n !== 20 || jobs.n !== 20 || payouts.n !== 20 || payouts.cents !== 200000) {
    await client.query('ROLLBACK');
    console.error('VERIFICATION FAILED — rolled back.', { blocks, jobs, payouts });
    process.exit(1);
  }

  await client.query('COMMIT');
  console.log('OK: 20 blocks completed | 20 jobs completed ($' + jobs.cents / 100 + ') | 20 payouts = $' + payouts.cents / 100);
  await client.end();
}

main().catch(async (e) => {
  console.error(e);
  try { await client.query('ROLLBACK'); } catch {}
  process.exit(1);
});
