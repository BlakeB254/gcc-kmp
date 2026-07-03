-- Route-v2: per-block inner boundary (private-property cutoff line).
-- Generated from OSM building frontages (admin regenerate endpoint) or
-- admin-edited; mobile falls back to a parallel 8m inset when null.
-- NOTE: applied directly to live Neon via psql on 2026-07-03 (same pattern
-- as 0025); this file documents the DDL.
ALTER TABLE blocks ADD COLUMN IF NOT EXISTS inner_boundary jsonb;
