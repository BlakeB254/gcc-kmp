-- 0053: server-side evidence checks (GPS plausibility, hotspot caps, photo
-- fingerprints, rectify reporter, AI review read model).
--
-- Hand-written (see CLAUDE.md: drizzle-kit generate is not trusted). Every
-- statement is guarded, so it is safe to re-run. 0052 was left free for the
-- parallel job-flow lane.
--
-- Apply: psql "$DATABASE_URL" -f site/src/db/migrations/0053_server_side_evidence_checks.sql
--
-- Run it WITHOUT --single-transaction / -1: section 7 uses CREATE INDEX
-- CONCURRENTLY (no write lock on the live scan_coverage_events table), which
-- cannot run inside a transaction block. Plain `psql -f` autocommits each
-- statement, which is what this file expects. Apply it BEFORE deploying the
-- app (the app writes the new columns/tables).

-- ── 1. GPS plausibility per route scan (lib/gps-plausibility.ts) ───────────
-- POST /api/scans/coverage drops implausible fixes (impossible speed,
-- future timestamps, duplicate-timestamp bursts, mock locations) before they
-- are recorded, and counts them here for the scan they would have counted
-- toward. Completion coverage is computed from the kept fixes only, because
-- dropped fixes are never written to scan_coverage_events.
ALTER TABLE route_scans ADD COLUMN IF NOT EXISTS gps_points_kept integer NOT NULL DEFAULT 0;
ALTER TABLE route_scans ADD COLUMN IF NOT EXISTS gps_points_dropped integer NOT NULL DEFAULT 0;
ALTER TABLE route_scans ADD COLUMN IF NOT EXISTS gps_drop_reasons jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE route_scans ADD COLUMN IF NOT EXISTS gps_suspect boolean NOT NULL DEFAULT false;

-- ── 2. Hotspot volume: the client's claim beside the server-capped value ──
-- scan_hotspots.estimated_volume_liters now holds the server-capped value
-- (lib/hotspot-checks.ts MAX_HOTSPOT_VOLUME_LITERS) that scan points use;
-- the raw client figure is kept for review.
ALTER TABLE scan_hotspots ADD COLUMN IF NOT EXISTS client_estimated_volume_liters double precision;

-- ── 3. Who reported a world observation (rectify anti-farming) ────────────
-- Null on rows written before this migration and on system writers (311
-- sync, DGX pipeline). Rectification falls back to scan_hotspots →
-- route_scans and community_reports for legacy rows.
ALTER TABLE world_observations ADD COLUMN IF NOT EXISTS reporter_worker_id integer;

-- ── 4. Photo fingerprints (lib/photo-fingerprint.ts) ──────────────────────
CREATE TABLE IF NOT EXISTS photo_fingerprints (
  id serial PRIMARY KEY,
  url varchar(500) NOT NULL,
  -- 'ok' = hashed; 'unverified' = the blob could not be fetched or decoded
  -- in time (the submission still went through).
  status varchar(16) NOT NULL DEFAULT 'ok',
  sha256 char(64),
  -- 64-bit difference hash, stored signed. Hamming distance in SQL:
  -- bit_count((a # b)::bit(64)).
  dhash bigint,
  byte_size integer,
  width integer,
  height integer,
  -- EXIF DateTimeOriginal read from the ORIGINAL upload (the stored blob is
  -- re-encoded without EXIF). Read as UTC when the photo carries no offset,
  -- so it is only trusted to within a day.
  exif_captured_at timestamptz,
  uploaded_by_worker_id integer,
  first_job_id integer REFERENCES jobs(id) ON DELETE SET NULL,
  first_scan_id integer REFERENCES route_scans(id) ON DELETE SET NULL,
  flags jsonb NOT NULL DEFAULT '[]'::jsonb,
  error varchar(200),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS photo_fingerprints_url_unique ON photo_fingerprints (url);
CREATE INDEX IF NOT EXISTS photo_fingerprints_sha256_idx ON photo_fingerprints (sha256);
CREATE INDEX IF NOT EXISTS photo_fingerprints_created_at_idx ON photo_fingerprints (created_at);

-- First job each photo was submitted for. Keys `url:<url>` / `sha256:<hex>`;
-- the primary key makes the first binding atomic, so two concurrent
-- submissions of one photo for two jobs cannot both win
-- (lib/photo-fingerprint-db.ts claimPhotoForJob).
CREATE TABLE IF NOT EXISTS photo_job_bindings (
  photo_key text PRIMARY KEY,
  job_id integer NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- ── 5. AI evidence review (rows written by the Python review worker) ──────
CREATE TABLE IF NOT EXISTS job_ai_reviews (
  job_id integer PRIMARY KEY REFERENCES jobs(id) ON DELETE CASCADE,
  status text NOT NULL CHECK (status IN ('ok', 'flagged', 'error')),
  verdict jsonb NOT NULL,
  model text NOT NULL,
  input_hash text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz
);

-- ── 6. Coverage ingest idempotency (lib/scan-coverage.ts) ─────────────────
-- `${worker_id}|${device_id}|${captured_at ISO}`; a retried batch hits the
-- unique index and inserts/pays nothing twice. Nullable, no default: adding
-- it is metadata-only, and existing rows stay NULL (NULLs never conflict).
ALTER TABLE scan_coverage_events ADD COLUMN IF NOT EXISTS dedupe_key text;

-- ── 7. Indexes on the live scan_coverage_events table (CONCURRENTLY) ──────
-- If one of these fails part-way, Postgres leaves an INVALID index that IF NOT
-- EXISTS would then skip. Check with:
--   SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;
-- and if one of the names below is listed: DROP INDEX CONCURRENTLY <name>;
-- then re-run this file.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS scan_coverage_events_dedupe_key_unique
  ON scan_coverage_events (dedupe_key);
-- Per-device trajectory anchor (lib/gps-plausibility-db.ts loadDeviceAnchor).
CREATE INDEX CONCURRENTLY IF NOT EXISTS scan_coverage_events_worker_device_captured_idx
  ON scan_coverage_events (worker_id, device_id, captured_at);
-- Rectify presence reads (lib/rectify-checks-db.ts).
CREATE INDEX CONCURRENTLY IF NOT EXISTS scan_coverage_events_worker_captured_idx
  ON scan_coverage_events (worker_id, captured_at);

-- ── 8. Backfill dedupe_key on pre-0053 rows (batched, live-table safe) ────
-- The FIRST row of each (worker_id, device_id, captured_at to the ms) group
-- gets the same key the app computes (lib/scan-coverage.ts
-- coverageDedupeKey: `${workerId}|${deviceId}|${ISO-8601 ms Z}`); later
-- duplicates stay NULL. Runs in id batches with a COMMIT after each, so no
-- long lock on the live table. Idempotent: only NULL rows whose key is not
-- taken are touched, so an interrupted run (e.g. a unique violation from a
-- fix replayed mid-backfill) is finished by re-running this file. Section 7's
-- (worker_id, device_id, captured_at) index makes the group check cheap.
CREATE OR REPLACE PROCEDURE gcc_0053_backfill_coverage_dedupe_key(batch_size integer DEFAULT 5000)
LANGUAGE plpgsql AS $$
DECLARE
  lo bigint;
  hi bigint;
  max_id bigint;
BEGIN
  SELECT coalesce(min(id), 1), coalesce(max(id), 0) INTO lo, max_id
    FROM scan_coverage_events WHERE dedupe_key IS NULL AND worker_id IS NOT NULL;
  WHILE lo <= max_id LOOP
    hi := lo + batch_size - 1;
    UPDATE scan_coverage_events e
       SET dedupe_key = e.worker_id::text || '|' || e.device_id || '|'
         || to_char(e.captured_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
     WHERE e.id BETWEEN lo AND hi
       AND e.dedupe_key IS NULL
       AND e.worker_id IS NOT NULL
       AND NOT EXISTS (
         SELECT 1 FROM scan_coverage_events d
          WHERE d.worker_id = e.worker_id AND d.device_id = e.device_id
            AND d.captured_at >= date_trunc('milliseconds', e.captured_at)
            AND d.captured_at < date_trunc('milliseconds', e.captured_at) + interval '1 millisecond'
            AND d.id < e.id)
       AND NOT EXISTS (
         SELECT 1 FROM scan_coverage_events k
          WHERE k.dedupe_key = e.worker_id::text || '|' || e.device_id || '|'
            || to_char(e.captured_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'));
    COMMIT;
    lo := hi + 1;
  END LOOP;
END
$$;
CALL gcc_0053_backfill_coverage_dedupe_key();
DROP PROCEDURE IF EXISTS gcc_0053_backfill_coverage_dedupe_key(integer);
