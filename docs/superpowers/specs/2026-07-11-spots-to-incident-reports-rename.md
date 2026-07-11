# "Spots" → "Incident Reports" — platform-wide rename — Design stub

**Date:** 2026-07-11 · **Status:** PLANNED, needs a dedicated session — staged so nothing
breaks the deployed app mid-rename · **Requested by:** Blake ("rename spots to incident
reports everywhere across the platform and backend, even DB tables/columns")

## Naming decision
User-facing + code term: **incident report** (GCC-owned, derived from scans and community
submissions). The World Twin map already uses this language.

## Why staged (the constraint that matters)
The deployed iOS app serializes DTOs by field NAME (`ObservedSpotItem`, `photoKeys`,
`observedItems` are wire format), and prod DB tables are live. A big-bang rename bricks
older app builds. Order of operations:

1. **Copy/UI layer (safe now):** site pages, app screen titles/strings, admin nav —
   "spot" → "incident report". No wire/schema change. SpotScreen stays internally named
   until step 4.
2. **DB (needs one migration + mirrors):** `community_reports` KEEPS its name (it IS the
   incident-report table; add a COMMENT). Rename `spot_verifications` →
   `incident_report_verifications`, `scan_hotspot_id` → `incident_anchor_id` (verify all
   readers), points enum values stay (append-only pg enums; add new `incident_*` actions,
   stop writing old). Hand-written migration 0036 + Tables.kt mirrors + drizzle schema in
   ONE PR per repo policy.
3. **API aliases:** add `/api/incident-reports/*` routes delegating to the community-report
   handlers; keep old paths until app versions <N are retired (analytics on usage).
4. **DTO/wire rename (last, coordinated with a forced app update):** `ObservedSpotItem` →
   `IncidentItem` with @SerialName back-compat annotations so old manifests still parse
   (the R2 archive of manifests is forever — parsers must accept BOTH names permanently).
5. **Data migration:** none needed for rows (tables keep data); backfill
   `world_observations.community_report_id` links where derivable.

## Also requested, shipped separately (2026-07-11)
Observation photos now render on the World Twin map popups (mediaUrl). GCC site/app
already display them via their own surfaces.
