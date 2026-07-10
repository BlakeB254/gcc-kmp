-- Phase-3 reconstruction queue (docs/superpowers/specs/
-- 2026-07-10-phase3-reconstruction-queue-and-prototype-design.md §4).
-- Hand-written (drizzle-kit generate untrusted past 0015); apply via psql
-- and insert a drizzle.__drizzle_migrations tracking row.

CREATE TABLE IF NOT EXISTS reconstruction_jobs (
  id               serial PRIMARY KEY,
  session_id       varchar(64) NOT NULL,
  location_cell_id varchar(32) NOT NULL,
  status           varchar(16) NOT NULL DEFAULT 'queued',
  attempts         integer NOT NULL DEFAULT 0,
  leased_until     timestamptz,
  last_error       text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  completed_at     timestamptz
);

CREATE UNIQUE INDEX IF NOT EXISTS reconstruction_jobs_session_id_key
  ON reconstruction_jobs (session_id);
CREATE INDEX IF NOT EXISTS reconstruction_jobs_claim_idx
  ON reconstruction_jobs (status, created_at);
CREATE INDEX IF NOT EXISTS reconstruction_jobs_cell_idx
  ON reconstruction_jobs (location_cell_id);

-- Two writers record reconstruction assets (the DGX worker at publish time,
-- the Ktor indexer mirroring R2 on its sweep) -- both must be idempotent on
-- r2_key, so enforce it. The indexer already inserts WHERE NOT EXISTS
-- (r2_key); the worker inserts ON CONFLICT (r2_key) DO NOTHING.
CREATE UNIQUE INDEX IF NOT EXISTS reconstruction_assets_r2_key_key
  ON reconstruction_assets (r2_key);
