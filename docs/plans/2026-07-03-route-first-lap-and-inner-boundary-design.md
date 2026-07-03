# Route Fulfillment v2: Guided First-Lap Capture + Inner Property Boundary — Design

**Date:** 2026-07-03 · **Status:** approved direction, next work package
**Source:** product direction from field testing (build 111 era)

## Goal

Route (block) fulfillment adopts the environment-scan system:

1. **Guided first lap** — the app directs the worker around the route's first
   lap with turn-by-turn style prompts; the whole lap records as ONE capture
   session (position + facing direction at ~1Hz + photos), documenting the
   block's baseline state via the session-manifest → R2 pipeline
   (`/api/captures/upload-url`, EnvScanManifest — reuse, add `lapKind:
   "baseline" | "clean"`).
2. **Cleanup + clean scan** — after the baseline lap identifies cleanable
   areas, the worker cleans them and records a second, "clean" scan session
   over the same cells (rectification per cell rides the existing coverage
   ledger + rectify endpoints).
3. **Double-lined route** — each route gains an INNER boundary polyline in
   addition to the block outline: the inner line offsets away from private
   property (homes' frontages). Between the lines = the public scan corridor.
   - Data: `routes.innerBoundary: GeoJSONPolygon` (server-authored; generated
     as an inset of blockBoundary by a per-route setback, admin-tunable;
     DGX parcel data can refine it later).
   - Recording exclusion: coverage cells and world observations inside the
     inner polygon are NOT recorded and NOT part of the required first-lap
     corridor (client: CoverageTracker corridor = between-lines band;
     server: recordWorldObservation excluded when inside innerBoundary —
     extends the existing exclusion-zone mechanism).
4. **Physical cutoff in the scanner** — with the scanner open on a block
   cleaning, the AR view shows where the inner boundary sits in physical
   space (a vertical "curtain" line rendered via ArRouteOverlay's existing
   lat/lng→AR-local math, amber, distinct from the outer red boundary veil).
5. **Minimap scanned/unscanned for routes** — the route scanner's minimap
   reuses ScanMinimapOverlay: green = scanned this session, fog/amber =
   never/stale from the ledger, clipped to the between-lines corridor.

## Product rule (from this direction)

**Bounties must originate from verified scans**: a bounty may only be created
from a report whose capture went through the environment/route scan system
(has a session manifest / capture attestation). Enforce at POST /api/bounties
(reject reports without attestation), and the admin UI mirrors it.

## Build sequence (suggested)

1. Server: `innerBoundary` column + inset-generation script + exclusion in
   recordWorldObservation/coverage classification.
2. Client geometry: corridor-between-lines in CoverageTracker; map render of
   the double line (GccMap route layers).
3. Guided first lap: waypoint sequencing over the corridor (AiDirectionEngine
   extension) + session recorder attach (lapKind=baseline) + R2 upload on
   scout completion.
4. AR cutoff curtain + route-mode minimap overlay.
5. Clean-lap flow: per-cell rectification sweep + lapKind=clean session.
6. Bounty verified-scan gate.
