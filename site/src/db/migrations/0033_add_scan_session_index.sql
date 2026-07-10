-- 0033 — scan-session index (hand-written, idempotent).
--
-- Indexes the previously-orphaned R2 capture blobs (gcc-scan-storage) into Neon
-- so sessions are queryable and linkable to coverage/reconstruction. Populated
-- by the Kotlin backend (`backend/`, the scan control-plane), which parses each
-- R2 manifest (the shared EnvScanManifest) and mirrors it here.
--
-- Applied to prod via psql on 2026-07-10 (this file is the recorded artifact;
-- `drizzle-kit generate` is not trustworthy past 0015 — see CLAUDE.md). Safe to
-- re-run: every statement is guarded.

CREATE TABLE IF NOT EXISTS scan_sessions (
  id                     serial PRIMARY KEY,
  session_id             varchar(64)  NOT NULL,
  device_id              varchar(64)  NOT NULL,
  device_model           varchar(64),
  platform               varchar(16),
  os_version             varchar(32),
  lap_kind               varchar(16),
  route_id               integer,
  location_cell_id       varchar(32),
  started_at             timestamptz,
  completed_at           timestamptz,
  photo_count            integer      NOT NULL DEFAULT 0,
  chunk_count            integer      NOT NULL DEFAULT 0,
  observed_item_count    integer      NOT NULL DEFAULT 0,
  scanned_cell_count     integer      NOT NULL DEFAULT 0,
  has_object_capture     boolean      NOT NULL DEFAULT false,
  object_capture_r2_key  varchar(500),
  manifest_r2_key        varchar(500) NOT NULL,
  total_bytes            bigint,
  reconstruction_version integer,
  reconstructed_at       timestamptz,
  indexed_at             timestamptz  NOT NULL DEFAULT now(),
  created_at             timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS scan_sessions_session_id_key ON scan_sessions (session_id);
CREATE INDEX IF NOT EXISTS scan_sessions_cell_completed_idx ON scan_sessions (location_cell_id, completed_at);
CREATE INDEX IF NOT EXISTS scan_sessions_unreconstructed_idx ON scan_sessions (completed_at) WHERE reconstruction_version IS NULL;

CREATE TABLE IF NOT EXISTS capture_chunks (
  id               serial PRIMARY KEY,
  session_id       varchar(64)  NOT NULL,
  location_cell_id varchar(32)  NOT NULL,
  artifact_type    varchar(24)  NOT NULL,
  r2_key           varchar(500) NOT NULL,
  format           varchar(16),
  byte_size        bigint,
  captured_at      timestamptz,
  indexed_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS capture_chunks_r2_key_key ON capture_chunks (r2_key);
CREATE INDEX IF NOT EXISTS capture_chunks_session_idx ON capture_chunks (session_id);
CREATE INDEX IF NOT EXISTS capture_chunks_cell_idx ON capture_chunks (location_cell_id);
