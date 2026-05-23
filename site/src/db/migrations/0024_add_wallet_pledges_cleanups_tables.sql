-- Create the four tables that the Ktor server's Exposed declarations expect
-- but were never deployed to prod: sponsor_wallets, wallet_transactions,
-- bounty_pledges, volunteer_cleanups.
--
-- Two of these (bounty_pledges, volunteer_cleanups) are reached by mounted
-- routes (bountyRoutes/adminBountyRoutes and spotRoutes' volunteer-cleanup
-- endpoints respectively), so without the tables the mobile app crashes on
-- first call. The other two (sponsor_wallets, wallet_transactions) are not
-- yet hit by a mounted Ktor route but are referenced as FK targets, so they
-- have to exist alongside.
--
-- Column types and constraints match `server/.../db/tables/ServerOnlyTables.kt`.
-- Idempotent (IF NOT EXISTS throughout) so re-running is safe.

CREATE TABLE IF NOT EXISTS "sponsor_wallets" (
  "id" serial PRIMARY KEY,
  "sponsor_id" integer REFERENCES "sponsors"("id"),
  "worker_id" integer REFERENCES "workers"("id"),
  "total_balance" integer NOT NULL DEFAULT 0,
  "general_fund_balance" integer NOT NULL DEFAULT 0,
  "route_fund_balance" integer NOT NULL DEFAULT 0,
  "bounty_fund_balance" integer NOT NULL DEFAULT 0,
  "total_deposited" integer NOT NULL DEFAULT 0,
  "total_spent" integer NOT NULL DEFAULT 0,
  "created_at" timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at" timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS "wallet_transactions" (
  "id" serial PRIMARY KEY,
  "wallet_id" integer NOT NULL REFERENCES "sponsor_wallets"("id"),
  "type" varchar(50) NOT NULL,
  "fund_type" varchar(50) NOT NULL,
  "amount" integer NOT NULL,
  "balance_after" integer NOT NULL,
  "reference_id" integer,
  "reference_type" varchar(50),
  "description" text,
  "stripe_payment_intent_id" varchar(255),
  "created_at" timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS "bounty_pledges" (
  "id" serial PRIMARY KEY,
  "bounty_id" integer NOT NULL REFERENCES "bounties"("id"),
  "pledger_type" varchar(50) NOT NULL,
  "pledger_id" integer NOT NULL,
  "wallet_id" integer NOT NULL REFERENCES "sponsor_wallets"("id"),
  "amount" integer NOT NULL,
  "status" varchar(50) NOT NULL DEFAULT 'active',
  "renewal_count" integer NOT NULL DEFAULT 0,
  "created_at" timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at" timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS "volunteer_cleanups" (
  "id" serial PRIMARY KEY,
  "report_id" integer NOT NULL REFERENCES "community_reports"("id"),
  "worker_id" integer NOT NULL REFERENCES "workers"("id"),
  "before_media_urls" jsonb NOT NULL,
  "after_media_urls" jsonb NOT NULL,
  "trash_heap_media_url" varchar(2048),
  "ai_trash_analysis" jsonb,
  "location_lat" double precision NOT NULL,
  "location_lng" double precision NOT NULL,
  "location_verified" boolean NOT NULL DEFAULT false,
  "started_at" timestamp with time zone NOT NULL,
  "completed_at" timestamp with time zone,
  "duration_minutes" integer,
  "pdf_url" varchar(2048),
  "status" varchar(50) NOT NULL DEFAULT 'in_progress',
  "verification_notes" text,
  "points_awarded" integer NOT NULL DEFAULT 0,
  "created_at" timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at" timestamp with time zone NOT NULL DEFAULT now()
);

-- Hot-path FK indexes (Postgres doesn't auto-index FK columns).
CREATE INDEX IF NOT EXISTS "sponsor_wallets_sponsor_id_idx"   ON "sponsor_wallets" ("sponsor_id");
CREATE INDEX IF NOT EXISTS "sponsor_wallets_worker_id_idx"    ON "sponsor_wallets" ("worker_id");
CREATE INDEX IF NOT EXISTS "wallet_transactions_wallet_id_idx" ON "wallet_transactions" ("wallet_id");
CREATE INDEX IF NOT EXISTS "bounty_pledges_bounty_id_idx"     ON "bounty_pledges" ("bounty_id");
CREATE INDEX IF NOT EXISTS "bounty_pledges_wallet_id_idx"     ON "bounty_pledges" ("wallet_id");
CREATE INDEX IF NOT EXISTS "volunteer_cleanups_report_id_idx" ON "volunteer_cleanups" ("report_id");
CREATE INDEX IF NOT EXISTS "volunteer_cleanups_worker_id_idx" ON "volunteer_cleanups" ("worker_id");
