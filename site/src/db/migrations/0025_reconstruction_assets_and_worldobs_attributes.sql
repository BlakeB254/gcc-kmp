-- Hand-written (not drizzle-kit generate) per this repo's documented
-- migration-chain gaps (see CLAUDE.md's schema-ownership policy). Apply via
-- psql and record a row in drizzle.__drizzle_migrations, same as prior
-- hand-written migrations in this chain (e.g. 0017-0019).

-- 1. reconstruction_assets — added to schema.ts on this branch, never migrated.
CREATE TABLE IF NOT EXISTS "reconstruction_assets" (
	"id" serial PRIMARY KEY NOT NULL,
	"location_cell_id" varchar(32) NOT NULL,
	"asset_type" varchar(20) NOT NULL,
	"format" varchar(10) NOT NULL,
	"r2_key" varchar(500) NOT NULL,
	"version" integer NOT NULL,
	"source_scan_coverage_event_ids" jsonb NOT NULL,
	"activated_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
CREATE INDEX IF NOT EXISTS "reconstruction_assets_cell_version_idx" ON "reconstruction_assets" ("location_cell_id","version");

-- 2. world_observations: generalize litter-specific metric columns into a
-- flexible `attributes` jsonb payload, so future domains (vegetation health
-- grade, construction progress, ...) don't each need their own migration.
--
-- IMPORTANT: this backfills existing rows' severity/estimated_volume_liters/
-- estimated_weight_lbs into `attributes` BEFORE dropping those columns, so no
-- existing observation loses data. If you instead run `drizzle-kit push`
-- directly against a database that hasn't had this migration applied, it will
-- diff schema.ts's new shape against the old live columns and DROP them
-- without this backfill — apply this migration first.
ALTER TABLE "world_observations" ADD COLUMN IF NOT EXISTS "attributes" jsonb;

UPDATE "world_observations"
SET "attributes" = (
	SELECT jsonb_object_agg(kv.key, kv.value)
	FROM (VALUES
		('severity', to_jsonb(severity)),
		('estimatedVolumeLiters', to_jsonb(estimated_volume_liters)),
		('estimatedWeightLbs', to_jsonb(estimated_weight_lbs))
	) AS kv(key, value)
	WHERE kv.value IS NOT NULL AND kv.value != 'null'::jsonb
)
WHERE "attributes" IS NULL
  AND (severity IS NOT NULL OR estimated_volume_liters IS NOT NULL OR estimated_weight_lbs IS NOT NULL);

ALTER TABLE "world_observations" DROP COLUMN IF EXISTS "severity";
ALTER TABLE "world_observations" DROP COLUMN IF EXISTS "estimated_volume_liters";
ALTER TABLE "world_observations" DROP COLUMN IF EXISTS "estimated_weight_lbs";
