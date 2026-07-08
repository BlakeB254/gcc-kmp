-- Hand-written migration (see CLAUDE.md: drizzle-kit generate is not trusted
-- until the snapshot chain is repaired; db:push against schema.ts is the
-- canonical deploy mechanism and this file is the recorded artifact).
--
-- APPLY: this migration is applied to prod via `psql` BY THE HUMAN (Blake) on
-- their word — do not run it yourself. After applying, insert a row into
-- drizzle.__drizzle_migrations so the tracking table stays current.
--
-- MERGE ACTION: additive only, safe to apply independently of migration order
-- beyond 0031 — no FK constraints (osm_id is the natural key, matching the
-- "hot-path lookup columns without a literal REFERENCES" pattern used by
-- scan_coverage_events / world_observations elsewhere).
--
-- Scan-visibility rework Workstream 1 — real OSM building footprints backing
-- the indoor gate. building_footprints holds one row per OSM building way
-- (bbox cols indexed for intersection lookup, full GeoJSON ring in polygon);
-- building_footprint_tiles is the per-tile fetch ledger giving each ~1.1km
-- tile a 30-day TTL so Overpass is hit at most once per tile.

CREATE TABLE IF NOT EXISTS "building_footprints" (
	"id" serial PRIMARY KEY NOT NULL,
	"osm_id" bigint,
	"source" varchar(16) DEFAULT 'overpass' NOT NULL,
	"tile_key" varchar(32) NOT NULL,
	"min_lng" double precision NOT NULL,
	"min_lat" double precision NOT NULL,
	"max_lng" double precision NOT NULL,
	"max_lat" double precision NOT NULL,
	"polygon" jsonb NOT NULL,
	"building_type" varchar(64),
	"fetched_at" timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "building_footprint_tiles" (
	"tile_key" varchar(32) PRIMARY KEY NOT NULL,
	"fetched_at" timestamp with time zone DEFAULT now() NOT NULL,
	"footprint_count" integer DEFAULT 0 NOT NULL
);

CREATE INDEX IF NOT EXISTS "building_footprints_bbox_idx"
	ON "building_footprints" ("min_lng", "max_lng", "min_lat", "max_lat");
CREATE INDEX IF NOT EXISTS "building_footprints_tile_idx"
	ON "building_footprints" ("tile_key");
CREATE UNIQUE INDEX IF NOT EXISTS "building_footprints_source_osm_idx"
	ON "building_footprints" ("source", "osm_id");
