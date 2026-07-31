-- Immutable verified reconstruction candidates and atomic activation.
-- Hand-written to match the post-0015 migration policy used by 0025-0039.
-- Every statement is replay-safe so a partially applied/manual migration can
-- be completed without replacing or duplicating an existing asset.

ALTER TABLE reconstruction_assets
  ADD COLUMN IF NOT EXISTS asset_kind varchar(20) NOT NULL DEFAULT 'legacy';
ALTER TABLE reconstruction_assets
  ADD COLUMN IF NOT EXISTS processing_status varchar(20) NOT NULL DEFAULT 'published';
ALTER TABLE reconstruction_assets
  ADD COLUMN IF NOT EXISTS review_status varchar(20) NOT NULL DEFAULT 'not_required';
ALTER TABLE reconstruction_assets
  ADD COLUMN IF NOT EXISTS reviewed_at timestamptz;
ALTER TABLE reconstruction_assets
  ADD COLUMN IF NOT EXISTS reviewed_by varchar(128);
ALTER TABLE reconstruction_assets
  ADD COLUMN IF NOT EXISTS parent_asset_id bigint;
ALTER TABLE reconstruction_assets
  ADD COLUMN IF NOT EXISTS manifest jsonb;
ALTER TABLE reconstruction_assets
  ADD COLUMN IF NOT EXISTS asset_sha256 varchar(64);
ALTER TABLE reconstruction_assets
  ADD COLUMN IF NOT EXISTS rejected_at timestamptz;
ALTER TABLE reconstruction_assets
  ADD COLUMN IF NOT EXISTS rejection_reasons jsonb;

-- If a previous partial/manual migration created these columns as nullable,
-- restore the intended legacy-safe defaults before enforcing NOT NULL.
UPDATE reconstruction_assets SET asset_kind='legacy' WHERE asset_kind IS NULL;
UPDATE reconstruction_assets SET processing_status='published' WHERE processing_status IS NULL;
UPDATE reconstruction_assets SET review_status='not_required' WHERE review_status IS NULL;
ALTER TABLE reconstruction_assets ALTER COLUMN asset_kind SET DEFAULT 'legacy';
ALTER TABLE reconstruction_assets ALTER COLUMN asset_kind SET NOT NULL;
ALTER TABLE reconstruction_assets ALTER COLUMN processing_status SET DEFAULT 'published';
ALTER TABLE reconstruction_assets ALTER COLUMN processing_status SET NOT NULL;
ALTER TABLE reconstruction_assets ALTER COLUMN review_status SET DEFAULT 'not_required';
ALTER TABLE reconstruction_assets ALTER COLUMN review_status SET NOT NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid = 'reconstruction_assets'::regclass
      AND conname = 'reconstruction_assets_parent_asset_id_fkey'
  ) THEN
    ALTER TABLE reconstruction_assets
      ADD CONSTRAINT reconstruction_assets_parent_asset_id_fkey
      FOREIGN KEY (parent_asset_id) REFERENCES reconstruction_assets(id);
  END IF;
END $$;

CREATE OR REPLACE FUNCTION reconstruction_assets_keep_verified_content_immutable()
RETURNS trigger AS $$
BEGIN
  IF OLD.asset_kind='verified' AND (
       NEW.location_cell_id IS DISTINCT FROM OLD.location_cell_id
    OR NEW.asset_type IS DISTINCT FROM OLD.asset_type
    OR NEW.format IS DISTINCT FROM OLD.format
    OR NEW.r2_key IS DISTINCT FROM OLD.r2_key
    OR NEW.version IS DISTINCT FROM OLD.version
    OR NEW.source_scan_coverage_event_ids IS DISTINCT FROM OLD.source_scan_coverage_event_ids
    OR NEW.asset_kind IS DISTINCT FROM OLD.asset_kind
    OR NEW.parent_asset_id IS DISTINCT FROM OLD.parent_asset_id
    OR NEW.manifest IS DISTINCT FROM OLD.manifest
    OR NEW.asset_sha256 IS DISTINCT FROM OLD.asset_sha256
    OR NEW.created_at IS DISTINCT FROM OLD.created_at
  ) THEN
    RAISE EXCEPTION 'verified reconstruction candidate content is immutable';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS reconstruction_assets_keep_verified_content_immutable
  ON reconstruction_assets;
CREATE TRIGGER reconstruction_assets_keep_verified_content_immutable
  BEFORE UPDATE ON reconstruction_assets
  FOR EACH ROW EXECUTE FUNCTION reconstruction_assets_keep_verified_content_immutable();

-- Repair only an impossible partially migrated state before adding the
-- invariant: retain the highest verified version as active and preserve all
-- older immutable rows as superseded history.
WITH duplicate_active AS (
  SELECT id, row_number() OVER (
    PARTITION BY location_cell_id
    ORDER BY version DESC, activated_at DESC, id DESC
  ) AS active_rank
  FROM reconstruction_assets
  WHERE asset_kind='verified'
    AND processing_status='published'
    AND activated_at IS NOT NULL
)
UPDATE reconstruction_assets AS asset
SET processing_status='superseded'
FROM duplicate_active
WHERE asset.id=duplicate_active.id AND duplicate_active.active_rank > 1;

CREATE UNIQUE INDEX IF NOT EXISTS reconstruction_assets_one_active_verified_cell
  ON reconstruction_assets(location_cell_id)
  WHERE asset_kind='verified'
    AND processing_status='published'
    AND activated_at IS NOT NULL;
