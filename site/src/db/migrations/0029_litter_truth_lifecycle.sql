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

-- (diagnostic_status added by Task 4 below)
