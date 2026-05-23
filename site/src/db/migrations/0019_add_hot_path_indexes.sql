-- Add indexes on hot-path foreign keys and status columns.
--
-- Postgres does NOT auto-index foreign-key columns, so queries like "all jobs
-- for worker X", "all transactions for sponsor Y", or "all points for worker
-- Z" table-scan once tables grow. The corresponding declarations in
-- site/src/db/schema.ts are the source of truth; this file applies them.
--
-- Idempotent (uses IF NOT EXISTS) so re-running on a partially-applied DB
-- is safe. Hand-written rather than generated because the drizzle-kit
-- snapshot chain has pre-existing collisions (0008/0009/0010 duplicate
-- filenames; journal missing 0010-0012 and 0017-0018) that need a
-- separate repair pass before drizzle-kit generate works again.

-- jobs: most-queried table after workers
CREATE INDEX IF NOT EXISTS "jobs_worker_id_idx" ON "jobs" ("worker_id");
CREATE INDEX IF NOT EXISTS "jobs_block_id_idx"  ON "jobs" ("block_id");
CREATE INDEX IF NOT EXISTS "jobs_status_idx"    ON "jobs" ("status");

-- transactions: sponsor wall, sponsor profile, block-funding queries
CREATE INDEX IF NOT EXISTS "transactions_sponsor_id_idx" ON "transactions" ("sponsor_id");
CREATE INDEX IF NOT EXISTS "transactions_block_id_idx"   ON "transactions" ("block_id");
CREATE INDEX IF NOT EXISTS "transactions_status_idx"     ON "transactions" ("status");

-- community_reports: map rendering, "my reports" panel, moderation queue
CREATE INDEX IF NOT EXISTS "community_reports_reporter_id_idx"     ON "community_reports" ("reporter_id");
CREATE INDEX IF NOT EXISTS "community_reports_service_area_id_idx" ON "community_reports" ("service_area_id");
CREATE INDEX IF NOT EXISTS "community_reports_near_block_id_idx"   ON "community_reports" ("near_block_id");
CREATE INDEX IF NOT EXISTS "community_reports_status_idx"          ON "community_reports" ("status");

-- points_ledger: leaderboard, worker profile, "all points for this spot"
CREATE INDEX IF NOT EXISTS "points_ledger_worker_id_idx"  ON "points_ledger" ("worker_id");
CREATE INDEX IF NOT EXISTS "points_ledger_reference_idx"  ON "points_ledger" ("reference_type", "reference_id");
