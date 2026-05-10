-- Adds two columns to the points_ledger so admin grants have a durable
-- audit trail rather than relying on application logs.
--
--   reason                 — free-text justification (≤280 chars at app layer)
--   granted_by_worker_id   — admin who issued the grant; NULL for automated entries
--
-- Both nullable so existing rows (spot_created, verification_*, etc.)
-- remain valid without a backfill.
ALTER TABLE "points_ledger" ADD COLUMN IF NOT EXISTS "reason" text;
ALTER TABLE "points_ledger" ADD COLUMN IF NOT EXISTS "granted_by_worker_id" integer;
