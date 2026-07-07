-- Hand-written migration (see CLAUDE.md: drizzle-kit generate is not trusted
-- until the snapshot chain is repaired; db:push against schema.ts is the
-- canonical deploy mechanism and this file is the recorded artifact).
--
-- MERGE ACTION: additive only, safe to apply independently of migration
-- order beyond 0030 — no FK constraints (jobId/routeId/workerId/sessionId
-- are plain integers, matching the "hot-path lookup columns without a
-- literal REFERENCES" pattern used by route_scans/scan_hotspots elsewhere
-- in this file, since jobs/routes/workers/route_scans rows can be pruned
-- independently of a historical report).
--
-- Pillar 9 — block completion reports. One row per completed job, generated
-- server-side (site/src/lib/block-completion-report.ts) right after the job
-- completion PATCH (site/src/app/api/jobs/[id]/complete) succeeds, from the
-- job's linked route_scans session (duration, GPS track, areas cleaned via
-- world_observations state transitions, before/after photo pairs, weights,
-- counts). Report generation is non-fatal — job completion always succeeds
-- even if this insert never happens; a missing row means "still generating"
-- to API clients (see GET /api/jobs/[id]/completion-report, 404 semantics)
-- and admins can force a re-run via POST /api/admin/reports/[id]/regenerate.
--
-- job_id is UNIQUE: exactly one report per job, upserted on regenerate.
CREATE TABLE IF NOT EXISTS "block_completion_reports" (
	"id" serial PRIMARY KEY NOT NULL,
	"job_id" integer NOT NULL,
	"route_id" integer NOT NULL,
	"worker_id" integer NOT NULL,
	"session_id" integer NOT NULL,
	"started_at" timestamp with time zone,
	"completed_at" timestamp with time zone,
	"duration_seconds" integer,
	"gps_track" jsonb,
	"areas_cleaned" jsonb,
	"image_pairs" jsonb,
	"total_estimated_weight_lbs" double precision,
	"cleaned_estimated_weight_lbs" double precision,
	"counts_by_type" jsonb,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS "block_completion_reports_job_id_idx" ON "block_completion_reports" ("job_id");
CREATE INDEX IF NOT EXISTS "block_completion_reports_route_id_idx" ON "block_completion_reports" ("route_id");
CREATE INDEX IF NOT EXISTS "block_completion_reports_worker_id_idx" ON "block_completion_reports" ("worker_id");
