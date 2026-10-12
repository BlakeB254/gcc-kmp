-- Aggregate caps + second-admin approval for ad-hoc admin wallet credits
-- (sec/admin-credit-aggregate-cap). Numbered 0051: 0050 is the latest on
-- main (0047–0050 all taken; two earlier parallel PRs once both minted 0047,
-- so check `ls site/src/db/migrations | tail` before reusing this number).
--
-- admin_credit_daily_totals  one row per (scope, subject_id, UTC day).
--                            scope 'admin'  = what an admin credited today
--                            scope 'target' = what a worker received today
--                            POST /api/admin/workers/{id}/payout bumps both in
--                            ONE upsert whose DO UPDATE … WHERE refuses a total
--                            above the cap, so the cap holds under concurrent
--                            requests without a transaction (neon-http).
--                            Caps: ADMIN_CREDIT_DAILY_CAP_PER_ADMIN_CENTS
--                            (default 50000 = $500) and
--                            ADMIN_CREDIT_DAILY_CAP_PER_TARGET_CENTS
--                            (default 25000 = $250); an unparseable value
--                            falls back to the default, never higher.
-- pending_admin_credits      a credit above
--                            ADMIN_CREDIT_SECOND_APPROVAL_THRESHOLD_CENTS
--                            (default 10000 = $100) lands here as 'pending'.
--                            POST /api/admin/wallet-credits/{id}/approve by a
--                            DIFFERENT admin (not the requester, not the
--                            target) is a conditional UPDATE on status =
--                            'pending'; the winner writes the admin_credit
--                            ledger row (wallet_entry_id) under the request's
--                            idempotency key. /reject withdraws it and gives
--                            the daily capacity back.
--
-- Hand-written and idempotent, per the schema policy in CLAUDE.md. Additive:
-- two new tables, no existing row or column changes.
-- Apply BEFORE deploying the code: the payout route inserts into
-- admin_credit_daily_totals on every request once this ships.

CREATE TABLE IF NOT EXISTS "admin_credit_daily_totals" (
  "scope" varchar(10) NOT NULL,
  "subject_id" integer NOT NULL,
  "day" date NOT NULL,
  "total_cents" integer NOT NULL DEFAULT 0,
  CONSTRAINT "admin_credit_daily_totals_pk" PRIMARY KEY ("scope", "subject_id", "day")
);

CREATE TABLE IF NOT EXISTS "pending_admin_credits" (
  "id" serial PRIMARY KEY,
  "target_worker_id" integer NOT NULL,
  "amount_cents" integer NOT NULL,
  "reason" varchar NOT NULL,
  "idempotency_key" varchar NOT NULL,
  "created_by_admin_id" integer NOT NULL,
  "status" varchar(20) NOT NULL DEFAULT 'pending',
  "decided_by_admin_id" integer,
  "decided_at" timestamp with time zone,
  "decision_note" varchar,
  "wallet_entry_id" integer,
  "created_at" timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at" timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS "pending_admin_credits_status_idx"
  ON "pending_admin_credits" ("status");

-- One request per idempotency key; the same key becomes the ledger entry's key
-- on approval, so a retry of either step credits nothing twice.
CREATE UNIQUE INDEX IF NOT EXISTS "pending_admin_credits_idempotency_key_unique"
  ON "pending_admin_credits" ("idempotency_key");
