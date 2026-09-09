# GCC Environment Scan → VidMap dataset (schema v1.1)

Civic-lane catalog only. Rows are **observed** Twin-v4 GCC reconstruction
assets (`GET :7047/api/scans/manifest?route_to=twin-v4`). VidMap pose
fields are empty schema stubs (`status=not_run`, `reason=vidmap_not_installed`).
No invented cameras, GPS tracks, or walk videos.

- Schema: `schema.v1.json` (matches `scripts/reconstruction/vidmap_runner.py` `SCHEMA_VERSION=1`)
- Rows: `rows.jsonl` (22 exterior assets; 3 generated Twin items skipped)
- Warehouse: `platform.environment_scan_vidmap_reconstruction` (22 rows, 2026-08-31 t_2c949bac)
- Export: database `gcc_local` table `environment_scan_vidmap_reconstruction` (NOT Neon)
- Query: `GET :7017/api/v1/data/datasets/environment-scan-vidmap-reconstruction`
- Entity: `#1963` `gcc-environment-scan-vidmap-reconstruction` (`metadata.kind=dataset`)
- Projection: `gcc-environment-scan-vidmap-reconstruction` (consumer project gcc / entity 92)
- Privacy: `rayban_wwgo3126_douglass_park` refused (PRD, 2026-08-10)
- License: first-party GCC internal; VidMap engine Apache-2.0
- Chicago 311: related overlay only (entity 1977, pointer-only). Not copied.

P0 GPU run is still outstanding (`vidmap_runner.py --run` on one verified-outdoor walk bundle).
Export provenance: `export_t_2c949bac_provenance.json` and workspace `PROVENANCE.md`.
