-- 0036: session -> world-splat registration surface (PR #149 contract:
-- docs/governance/scan-splat-registration-contract.md).
--
-- geo_ref_event_count is written by the Ktor scan indexer at index time
-- (how many AR<->GPS pairings the manifest carries). The remaining fields
-- are written by the DGX reconstruction worker after the accuracy-weighted
-- similarity solve:
--   registration_status    'world' (merge-eligible) | 'local-only'
--   registration_residual_m  RMS of the solve, meters
--   world_registration     jsonb solve record: clock offset, per-segment
--                          {thetaDeg, eastM, northM, events, residualM},
--                          anchor lat/lng, method
-- Hand-written with IF NOT EXISTS guards per repo migration policy
-- (drizzle-kit generate untrusted past 0015); applied via psql + a
-- drizzle.__drizzle_migrations tracking row.

ALTER TABLE scan_sessions
  ADD COLUMN IF NOT EXISTS geo_ref_event_count integer NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS registration_status varchar(16),
  ADD COLUMN IF NOT EXISTS registration_residual_m real,
  ADD COLUMN IF NOT EXISTS world_registration jsonb;

-- "Which sessions are world-mergeable" is the query this exists for.
CREATE INDEX IF NOT EXISTS scan_sessions_registration_status_idx
  ON scan_sessions (registration_status)
  WHERE registration_status IS NOT NULL;
