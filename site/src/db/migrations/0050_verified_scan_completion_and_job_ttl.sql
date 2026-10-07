-- Server-verified scan/job completion + paid-job TTL policy (sec/verified-completion).
--
-- scan_coverage_events.route_scan_id  the route scan an event was walked under,
--                                   stamped by the SERVER at ingest (POST
--                                   /api/scans/coverage) from the caller's single
--                                   active route scan. Scan verification counts
--                                   only events carrying the scan's id. Existing
--                                   rows stay NULL (unattributed).
-- route_scans.lap_one_verified_at   stamped by PATCH /api/scans/{id}/phase at
--                                   scouting → scout_review only when the route
--                                   coverage the server computes from this scan's
--                                   attributed events reaches the lap-one
--                                   threshold (client proof still required too);
--                                   POST /api/scans/{id}/complete refuses a scan
--                                   without it.
-- route_scans.lap_one_coverage_ratio  that server-computed ratio (0..1).
-- route_scans.verified_coverage_ratio  route coverage computed server-side from
--                                   the attributed events at completion (0..1).
-- jobs.completion_scan_id           the scan that proved the job; UNIQUE so one
--                                   scan cannot complete two jobs.
-- jobs.start_reminder_sent_at       paid claimed-not-started push (4h), once per claim.
-- jobs.progress_reminder_sent_at    paid in-progress push (12h), once per run.
-- jobs.stalled_at                   paid in-progress 24h with no qualifying scan;
--                                   shown in GET /api/admin/jobs. Never auto-released.
--
-- route_scans.status 'abandoned' needs no DDL: the column is varchar(30), and
-- the Kotlin ScanStatus enum already has ABANDONED.
--
-- Idempotent: safe to run more than once. Additive: deletes and rewrites nothing.
-- Apply BEFORE deploying the code: full-row selects/returning of jobs,
-- route_scans and scan_coverage_events name every schema column, so the code
-- (including coverage ingest) fails on a DB without them.

ALTER TABLE "route_scans" ADD COLUMN IF NOT EXISTS "lap_one_verified_at" timestamp with time zone;
ALTER TABLE "route_scans" ADD COLUMN IF NOT EXISTS "verified_coverage_ratio" double precision;
ALTER TABLE "route_scans" ADD COLUMN IF NOT EXISTS "lap_one_coverage_ratio" double precision;

ALTER TABLE "scan_coverage_events" ADD COLUMN IF NOT EXISTS "route_scan_id" integer;

ALTER TABLE "jobs" ADD COLUMN IF NOT EXISTS "completion_scan_id" integer;
ALTER TABLE "jobs" ADD COLUMN IF NOT EXISTS "start_reminder_sent_at" timestamp with time zone;
ALTER TABLE "jobs" ADD COLUMN IF NOT EXISTS "progress_reminder_sent_at" timestamp with time zone;
ALTER TABLE "jobs" ADD COLUMN IF NOT EXISTS "stalled_at" timestamp with time zone;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'jobs_completion_scan_id_route_scans_id_fkey'
  ) THEN
    ALTER TABLE "jobs"
      ADD CONSTRAINT "jobs_completion_scan_id_route_scans_id_fkey"
      FOREIGN KEY ("completion_scan_id") REFERENCES "route_scans"("id");
  END IF;
END $$;

-- The column is new, so no existing row can clash. NULLs never collide.
CREATE UNIQUE INDEX IF NOT EXISTS "jobs_completion_scan_id_unique" ON "jobs" ("completion_scan_id");

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'scan_coverage_events_route_scan_id_route_scans_id_fkey'
  ) THEN
    ALTER TABLE "scan_coverage_events"
      ADD CONSTRAINT "scan_coverage_events_route_scan_id_route_scans_id_fkey"
      FOREIGN KEY ("route_scan_id") REFERENCES "route_scans"("id");
  END IF;
END $$;

-- "This scan's route coverage events" (lib/scan-completion.ts).
CREATE INDEX IF NOT EXISTS "scan_coverage_events_route_scan_id_idx"
  ON "scan_coverage_events" ("route_scan_id");

-- One active route scan per worker is enforced by POST /api/scans (409), NOT
-- by a unique partial index on (worker_id) WHERE status = 'in_progress':
-- production on 2026-10-07 had workers with two in_progress scans (worker 4:
-- scans 1, 2; worker 8: scans 5, 9 — all older than the 7-day abandon cutoff,
-- so the cron sweep will mark them abandoned), which would fail the index build.
