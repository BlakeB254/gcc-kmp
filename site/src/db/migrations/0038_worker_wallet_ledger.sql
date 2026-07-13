-- Worker wallet ledger (2026-07-13)
--
-- Earnings now land in an in-app wallet balance instead of requiring Stripe
-- Connect at approval time. workers.wallet_balance_cents is authoritative for
-- spend checks; worker_wallet_entries is the audit ledger. Withdrawals reuse
-- the payouts table, now creatable by the worker (initiated_by_worker_id).
--
-- Hand-written with IF NOT EXISTS guards per the repo's migration policy
-- (apply via psql + insert a row into drizzle.__drizzle_migrations).

ALTER TABLE workers
  ADD COLUMN IF NOT EXISTS wallet_balance_cents integer NOT NULL DEFAULT 0;

ALTER TABLE payouts
  ALTER COLUMN initiated_by_admin_id DROP NOT NULL;

ALTER TABLE payouts
  ADD COLUMN IF NOT EXISTS initiated_by_worker_id integer;

CREATE TABLE IF NOT EXISTS worker_wallet_entries (
  id serial PRIMARY KEY,
  worker_id integer NOT NULL,
  amount_cents integer NOT NULL,
  kind varchar NOT NULL,
  job_id integer,
  payout_id integer,
  note varchar,
  created_by_admin_id integer,
  idempotency_key varchar,
  created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS worker_wallet_entries_worker_id_idx
  ON worker_wallet_entries (worker_id);

CREATE UNIQUE INDEX IF NOT EXISTS worker_wallet_entries_idempotency_key_unique
  ON worker_wallet_entries (idempotency_key);

-- One primal earning credit per job, ever.
CREATE UNIQUE INDEX IF NOT EXISTS worker_wallet_entries_job_earning_unique
  ON worker_wallet_entries (job_id) WHERE kind = 'job_earning';

-- One refund per failed withdrawal, ever.
CREATE UNIQUE INDEX IF NOT EXISTS worker_wallet_entries_withdrawal_reversal_unique
  ON worker_wallet_entries (payout_id) WHERE kind = 'withdrawal_reversal';
