-- 0039 — capture-health columns (hand-written, idempotent).
--
-- Task 6 of docs/superpowers/specs/2026-07-14-3dgs-capture-reliability spec:
-- Task 5 added `EnvScanManifest.captureHealth` (session length, video
-- coverage seconds, thermal high-water mark, degraded/dropped chunk counts,
-- writer errors) to the shared model; the Kotlin backend (`backend/`
-- ScanIndexer) extracts it at index time into `scan_sessions` so the admin
-- review surface can flag splat-degraded sessions without parsing the pose
-- sidecar. `reconstruction_jobs.splat_skip_reason` is written by Task 7
-- (the DGX reconstruction worker) when a job is skipped on capture-health
-- grounds (e.g. thermal-degraded video, insufficient coverage).
--
-- File only in this task — NOT applied to prod yet; that is a separate
-- checkpoint gated on Blake's go-ahead (apply via psql +
-- drizzle.__drizzle_migrations tracking row, per CLAUDE.md — `drizzle-kit
-- generate` is not trustworthy past 0015). Safe to re-run: every statement
-- is guarded. All new columns are nullable: NULL means "session predates
-- capture health / not indexed yet", never zero degradation.

ALTER TABLE scan_sessions
  ADD COLUMN IF NOT EXISTS video_coverage_pct real,
  ADD COLUMN IF NOT EXISTS max_thermal_state varchar(16),
  ADD COLUMN IF NOT EXISTS dropped_video_chunks integer;

ALTER TABLE reconstruction_jobs
  ADD COLUMN IF NOT EXISTS splat_skip_reason varchar(32);
