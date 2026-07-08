-- Hand-written migration (see CLAUDE.md: drizzle-kit generate is not trusted
-- until the snapshot chain is repaired; db:push against schema.ts is the
-- canonical deploy mechanism and this file is the recorded artifact).
--
-- Amendment 2 / Task 1b (litter-truth lifecycle): scan submissions record
-- world observations only — no communityReports row. This adds the points
-- action the scan path now logs, and the bounty column that lets a bounty
-- be seeded from an observation instead of a report.
--
-- MERGE ACTION: this file is a HARD deploy dependency for the scan path —
-- without the enum value applied, the scan path's points-ledger insert
-- (action: 'observation_logged') 500s. Apply by hand via psql at merge time
-- (the established pattern for this repo's hand-written migrations); this
-- file is never run by drizzle-kit migrate / db:push.
ALTER TYPE "enum_points_ledger_action" ADD VALUE IF NOT EXISTS 'observation_logged';
ALTER TABLE "bounties" ADD COLUMN IF NOT EXISTS "trigger_observation_id" integer;

-- Task 4 (observed->diagnosed lifecycle): every insert through drizzle now
-- names this column explicitly (schema.ts), so this is a HARD deploy
-- dependency for EVERY world_observations insert/select, not just the scan
-- path — apply by hand via psql at merge time, same as the rest of this file.
ALTER TABLE "world_observations" ADD COLUMN IF NOT EXISTS "diagnostic_status" varchar(10) DEFAULT 'observed' NOT NULL;
