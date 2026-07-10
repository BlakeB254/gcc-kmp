# Chicago 311 Civic Loop — portal-grounded scan prompts + visual evidence — Design

**Date:** 2026-07-10 · **Status:** DGX/backend/API parts built this session; **mobile prompt UX
+ points value need Blake's review before the app change ships** · **Author:** Claude (DGX session)

## 1. The idea (Blake's brief, made concrete)

Ground GCC's map in the same premises as the Chicago Data Portal — data organized by the 77
community areas, sourced from the city's own 311 service requests — then close a loop the city
can't: **an open 311 report becomes a scan prompt in the GCC app, and the scan becomes visual
evidence attached to that report.** A user scanning near an open "Fly Dumping Complaint" gets
asked *"Do you see something matching this report?"* — scanning it earns bonus points, and the
backend starts tracking which 311 issues we hold photos/3D scans for, per community area. That
capture-rate analytic is the demonstrable innovation over the city's text-only reports.

Verified foundations (2026-07-10):
- A Chicago Data Portal sync already runs on this box (`~/data/scripts/sync_chicago_data.py` →
  `data-postgres-geo`), but it mirrors ROAD types only (potholes, street lights) and its
  `streets` sync silently truncates-then-fails (56 338 fetched, 0 rows — pre-existing bug,
  flagged not fixed here). It is not GCC-facing.
- The portal's live SODA API (`v6vf-nfxy`) carries the cleanup types GCC cares about; exact
  strings confirmed live: `Graffiti Removal Request`, `Fly Dumping Complaint`, `Sanitation
  Code Violation`, `Weed Removal Request`, `Clean Vacant Lot Request`, `Street Cleaning
  Request`, `Tree Debris Clean-Up Request`.
- Twin v4 natively renders manifest items with `scan_type: 'civic'` + a category palette; the
  World Twin service (cdx-platform) already feeds it per-project manifests.
- GCC already has the cell world model (`gridCell10m`, `cell_coverage_summary`,
  `world_observations`) and a mobile scan flow that flushes coverage every 30 s.

## 2. Why GCC needs its own mirror (not the geo DB, not live SODA)

The mobile app talks to the Next.js API on **Vercel**, which cannot reach the DGX's
`data-postgres-geo`, and per-scan-tick calls to the city's SODA API from serverless would be
slow, rate-limited, and flaky. So relevant 311 reports are mirrored into **GCC's Neon** by the
always-on Ktor backend (the DGX background job Blake asked for), keyed to the cell grid at
ingest time. Neon is then the one source for the app prompt, the map, the twin, and analytics.

## 3. Data model (Drizzle authoritative; migration 0035, hand-written)

```sql
civic_reports              -- mirror of GCC-relevant portal rows
  sr_number varchar(50) UNIQUE   -- the city's key; never invented by us
  sr_type, status, owner_department
  created_date, last_modified_date, closed_date timestamptz
  street_address, community_area int
  lat/lng double precision, location_cell_id varchar(32)  -- gridCell10m at ingest
  first_captured_at timestamptz  -- set when the FIRST GCC evidence lands
  synced_at timestamptz
  INDEX (location_cell_id), (status, sr_type), (community_area)

civic_report_evidence      -- our visual evidence, linked to the city's report
  sr_number varchar(50)          -- FK-by-convention to civic_reports
  session_id varchar(64)         -- the scan session that captured it
  observation_id int             -- optional world_observations link
  photo_r2_key varchar(500)      -- optional direct photo
  worker_id int                  -- who captured (points attribution)
  match_confidence varchar(8)    -- 'user' (user said "yes I see it") | 'auto' (future CV match)
  created_at timestamptz
  UNIQUE (sr_number, session_id)
```

Community-area organization comes free: `community_area` is the portal's own field, and all
rollups (`/api/civic/stats`) group by it — same premises as every portal dashboard.

## 4. Ingestion (Ktor backend — the DGX background job)

`CivicReportSync` in `backend/`: pulls SODA `v6vf-nfxy` filtered to the GCC type list
(configurable env `CIVIC_SR_TYPES`, default the seven above) with a `last_modified_date`
watermark, paginated, capped; upserts on `sr_number` (status transitions Open→Completed update
in place — we keep closed reports for analytics, the app only prompts on Open). Cell id is
computed with the SAME `gridCell10m` from `:shared` the mobile client uses (byte-equality
contract). Runs on the existing sweep cadence (`CIVIC_SYNC_INTERVAL_MINUTES`, default 360) +
`POST /admin/sync-civic` for manual runs. Optional `SOCRATA_APP_TOKEN` lifts throttling.

## 5. The scan-prompt loop (mobile — SPEC ONLY, needs review + MacBook build loop)

1. During an environment scan, alongside the existing 30 s coverage flush, the app calls
   `GET /api/civic/nearby?lat&lng&radiusM=75` (new, shipped this session) → open reports in
   nearby cells, each with `srNumber`, `srType`, `createdDate`, `streetAddress`, distance.
2. The scan UI surfaces at most ONE prompt per report per session, only when `captureAllowed`
   (outdoors) and inside the radius: *"311 report near you: 〈type〉, reported 〈date〉 at
   〈address〉. See it? Scan it."* Accept → the existing capture flow runs; the session records
   the association locally.
3. On scan completion the app POSTs `POST /api/civic/evidence` `{srNumber, sessionId,
   observationId?, photoKey?}` (shipped this session, mobile-authed). Server inserts
   `civic_report_evidence`, stamps `civic_reports.first_captured_at` if first, and awards the
   bonus via the existing `pointsLedger` (category `civic_capture`).
4. **Open for Blake:** bonus size (suggest: flat +25, first-evidence-only, per report per
   worker), whether a "No, it's gone" answer should also pay (it's useful signal — suggests the
   city can close the report), and prompt frequency caps.

## 6. Display

- **GCC map (close zoom)** and **World Twin 2D**: open civic reports as amber dots
  (`data_layers: [chicago_311]` in the twin's project config; GCC site map wiring is a
  follow-up alongside the world-model M2 work). Popup: type, age, address, evidence count.
- **World Twin 3D**: the same reports enter the Twin-v4 manifest as `scan_type: 'civic'`
  items with categories mapped to the viewer's palette — the city's data and our splats in one
  scene.
- Reports WITH GCC evidence render distinctly (green ring) — "we have eyes on this."

## 7. Analytics (the innovation story)

`GET /api/civic/stats` (shipped): per community area — open reports, reports with GCC
evidence, capture rate, by type. This is the headline number for partners: *"of the N open
sanitation-class 311 reports in North Lawndale, GCC holds photographic/3D evidence for X%."*
Later (not now): auto-matching world_observations to reports by cell+type (CV assist), and
push-style notifications ("3 new 311 reports in your usual area").

## 8. Non-goals now

Writing anything BACK to the city's 311 system (read-only integration); citywide bulk
backfill of closed historical reports (open + recent only); CV auto-matching; the streets
table fix in `sync_chicago_data.py` (flagged, separate concern).

## 9. Security addendum (same session)

Automated review flagged the evidence endpoint for IDOR on the referenced resources. Root
cause is a data-model gap: `scan_sessions` and `world_observations` carry NO worker
attribution (sessions are device-keyed), so per-worker ownership is structurally
unverifiable today. Hardening shipped: the session must exist and be completed; a
referenced observation must exist AND lie within 150 m of the report; `photoKey` must be a
well-formed `raw/{cell}/…` key whose cell is within 150 m of the report. **Hard
prerequisite before setting `CIVIC_CAPTURE_BONUS_POINTS` > 0: add worker attribution to
scan sessions (workerId on the manifest/session row) so evidence ownership is enforceable
— otherwise points can be farmed by attaching someone else's nearby artifacts.**
