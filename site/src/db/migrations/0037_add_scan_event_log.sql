-- 0037 — scan-session flight recorder (hand-written, idempotent).
--
-- Phase 1 of docs/prd/scan-session-replay-devops-prd.md: every scan session
-- uploads a gzipped JSONL event stream (events.jsonl.gz) alongside its
-- manifest; the Kotlin backend (`backend/` ScanIndexer) fetches it at index
-- time and extracts headline metrics into scan_sessions. crash_reports gains
-- r2_key for MetricKit diagnostics stored whole in R2
-- (diagnostics/metrickit/…, POST /api/crashes/diagnostics).
--
-- Applied to prod via psql on 2026-07-12 (Blake's go-ahead) + tracking row
-- inserted into drizzle.__drizzle_migrations (id 12). This file is the
-- recorded artifact; `drizzle-kit generate` is not trustworthy past 0015 —
-- see CLAUDE.md. Safe to re-run: every statement is guarded. All new
-- columns are nullable: NULL means "session predates the flight recorder /
-- log not indexed yet", never zero activity.

ALTER TABLE scan_sessions ADD COLUMN IF NOT EXISTS event_log_r2_key     varchar(500);
ALTER TABLE scan_sessions ADD COLUMN IF NOT EXISTS event_count          integer;
ALTER TABLE scan_sessions ADD COLUMN IF NOT EXISTS gate_flip_count      integer;
ALTER TABLE scan_sessions ADD COLUMN IF NOT EXISTS tap_count            integer;
ALTER TABLE scan_sessions ADD COLUMN IF NOT EXISTS confirm_count        integer;
ALTER TABLE scan_sessions ADD COLUMN IF NOT EXISTS reject_count         integer;
ALTER TABLE scan_sessions ADD COLUMN IF NOT EXISTS raycast_miss_count   integer;
ALTER TABLE scan_sessions ADD COLUMN IF NOT EXISTS detection_count      integer;
ALTER TABLE scan_sessions ADD COLUMN IF NOT EXISTS upload_attempt_count integer;
ALTER TABLE scan_sessions ADD COLUMN IF NOT EXISTS upload_failure_count integer;
ALTER TABLE scan_sessions ADD COLUMN IF NOT EXISTS error_count          integer;

ALTER TABLE crash_reports ADD COLUMN IF NOT EXISTS r2_key varchar(500);
