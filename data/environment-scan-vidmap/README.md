# GCC Environment Scan → VidMap dataset (schema v1.0)

Civic-lane catalog only. Rows are **observed** Twin-v4 GCC reconstruction
assets (`GET :7047/api/scans/manifest?route_to=twin-v4`). VidMap pose
fields are empty schema stubs (`status=not_run`, `reason=vidmap_not_installed`).
No invented cameras, GPS tracks, or walk videos.

- Schema: `schema.v1.json` (matches `scripts/reconstruction/vidmap_runner.py` `SCHEMA_VERSION=1`)
- Rows: `rows.jsonl` (22 exterior assets; 3 generated non-exterior Twin items skipped)
- Query: `GET :7017/api/v1/project-connections?project_id=122&kind=dataset`
- Entity: `#1963` `gcc-environment-scan-vidmap-reconstruction` (`metadata.kind=dataset`)
- Privacy: `rayban_wwgo3126_douglass_park` refused (PRD, 2026-08-10)
- License: first-party GCC internal; VidMap engine Apache-2.0

P0 GPU run is still outstanding (`vidmap_runner.py --run` on one verified-outdoor walk bundle).
