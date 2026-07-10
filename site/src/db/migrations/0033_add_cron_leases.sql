-- Single-writer leases for cron endpoints (see site/src/lib/cron-lease.ts).
--
-- Guarded with IF NOT EXISTS because `npm run db:push` is the canonical deploy
-- mechanism in this repo, not `drizzle-kit migrate` — this file is a recorded
-- artifact that must stay idempotent against an already-pushed schema.
CREATE TABLE IF NOT EXISTS "cron_leases" (
  "name" varchar(64) PRIMARY KEY NOT NULL,
  "locked_at" timestamp with time zone DEFAULT now() NOT NULL,
  "expires_at" timestamp with time zone NOT NULL
);
