-- 0044: route_scans block linkage + map-read perf.
-- Guarded/idempotent; applied by hand via psql + a drizzle.__drizzle_migrations
-- ledger insert (see CLAUDE.md "How migrations actually get to production").
-- Schema source of truth: site/src/db/schema.ts (routeScans.blockId,
-- cellCoverageSummary.lat/lng, worldObservations indexes).

-- 1) route_scans.block_id — the owning block, resolved via blocks.route_id
--    (1:1: each block owns one route). Lets route-scan observations run
--    block exclusion-zone checks (scans/[id]/hotspots).
ALTER TABLE route_scans ADD COLUMN IF NOT EXISTS block_id integer REFERENCES blocks(id);

-- Backfill existing scans from the route linkage. Stays NULL for routes no
-- block row points at — the reading side treats NULL as "no exclusion check".
UPDATE route_scans rs
SET block_id = b.id
FROM blocks b
WHERE rs.block_id IS NULL
  AND b.route_id = rs.route_id;

-- 2) cell_coverage_summary.lat/lng — denormalized cell-center coordinates so
--    /api/map/coverage can bbox-filter in SQL instead of fetching every row
--    and filtering in JS. Nullable by design; the reader keeps a JS fallback
--    for any row still NULL, and the upsert self-repairs rows on next scan.
ALTER TABLE cell_coverage_summary ADD COLUMN IF NOT EXISTS lat double precision;
ALTER TABLE cell_coverage_summary ADD COLUMN IF NOT EXISTS lng double precision;

-- Backfill = cellCenter() (site/src/lib/world-observations.ts): 10 m cell on
-- fixed 111000 / 82300 meters-per-degree scales — NOT cos(lat)-corrected;
-- byte-equality of the cell-id math across systems is a hard contract
-- (docs/CONVENTIONS.md), so these constants must match exactly.
UPDATE cell_coverage_summary
SET lat = (split_part(location_cell_id, ':', 1)::bigint + 0.5) * (10.0 / 111000),
    lng = (split_part(location_cell_id, ':', 2)::bigint + 0.5) * (10.0 / 82300)
WHERE lat IS NULL;

-- 3) world_observations had no indexes beyond its PK (verified live
--    2026-09-01) while being bbox-queried by 8 routes and cell-queried by the
--    supersession path on every observation write.
CREATE INDEX IF NOT EXISTS world_observations_current_lat_lng_idx ON world_observations (is_current, lat, lng);
CREATE INDEX IF NOT EXISTS world_observations_cell_idx ON world_observations (location_cell_id);
