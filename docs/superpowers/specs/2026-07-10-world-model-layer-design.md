# GCC World Model — Unified Spatial-Semantic Layer — Design

**Date:** 2026-07-10 · **Status:** DESIGN ONLY — do not build until Blake reviews ·
**Author:** Claude (DGX session)

**Relationship to canon:** Step 2 of the 2026-07-10 DGX brief. Unifies what already exists —
coverage (`cell_coverage_summary`, doc #8 `scan-and-coverage-model.md` §8), semantics
(`world_observations`, supersession per §9), 3D (`reconstruction_assets`, per the same-date
Phase-3 reconstruction spec) — behind one query surface keyed by `locationCellId`. It does NOT
introduce new storage; it names the contract over storage that exists.

## 1. The model in one paragraph

The world model is the answer to "what does GCC know about this patch of ground?" A patch is a
**cell** — the fixed ~3 m lattice id (`gridCell10m`, byte-equal client/server, the same id that
keys R2 `raw/{cellId}/…`). For any cell (or bbox of cells), the world model exposes four planes:
**coverage** (has anyone scanned here, how well, how fresh), **semantics** (what's here —
litter, vegetation, etc., current-state via supersession), **reconstruction** (is there a 3D
asset, which version, how to stream it), and **provenance** (which sessions/events back all of
the above). Every plane is already stored append-only with a rollup or current-flag; the world
model is the read contract that joins them.

## 2. Query surface

Three endpoints cover the three consumers (web map, mobile app, cdx-platform/Twin viewer). All
serve from existing tables; no new writes.

### 2.1 `GET /api/world-model/cells?bbox={w},{s},{e},{n}&zoom={z}` — the map plane

Returns per-cell rollups inside the bbox, shaped for map rendering:

```jsonc
{ "cells": [ {
    "cellId": "464650:-721828",
    "coverage": { "tier": "CLOSE", "lastScannedAt": "…", "sessionCount": 3 },
    "observations": { "litter": 4, "vegetation": 1 },   // current (is_current) counts by domain
    "reconstruction": { "version": 1, "activated": false, "assetTypes": ["splat-web"] } | null
} ], "cellSizeMeters": 3.0 }
```

- **Zoom aggregation:** below a zoom threshold (z < ~15) individual 3-m cells are meaningless
  and the payload explodes; the endpoint aggregates to coarser buckets (cell-id prefix
  truncation gives power-of-10 lat/lng buckets for free — no PostGIS needed) and returns
  `bucketSizeMeters` accordingly. At high zoom it returns raw cells.
- **The `LIMIT 5000` cliff** (`GET /api/map/coverage`, flagged in
  `production-readiness-and-scaling.md` §2) is not repeated here: bbox is mandatory, the
  server clamps bbox area per zoom, and rows are bounded by the clamp instead of a silent LIMIT.
- Existing `GET /api/map/coverage` and the observation map endpoints stay as-is and can later
  become thin views over this (migration, not big-bang).

### 2.2 `GET /api/world-model/cell/{cellId}` — the detail plane

Everything about one cell: full coverage summary, current observations (with photo refs),
reconstruction assets across versions (each with `assetUrl` per §4), contributing sessions
(id, completedAt, deviceModel — provenance for trust review later, per the 07-02 spec §3.2).

### 2.3 `GET /api/world-model/manifest?bbox=…` — the 3D/viewer plane

The streaming manifest for 3D clients: current reconstruction asset per cell in the bbox,
Twin-v4-compatible shape (the fusion branch already proved chicago-twin-viewer consumes exactly
this with zero viewer changes — `file_url` must be a same-origin path, the viewer string-concats
its base). Mobile AR and the web map's 3D layer consume the same manifest.

## 3. Versioning & supersession (the rules, stated once)

| Plane | Storage | Current-state rule |
|---|---|---|
| Coverage | `scan_coverage_events` (append-only log) → `cell_coverage_summary` rollup | rollup IS current; retraction recomputes from surviving events (already shipped) |
| Semantics | `world_observations` with `is_current` | new scan of a cell supersedes overlapping prior observations (already shipped) |
| 3D | `reconstruction_assets`, append-only `(cellId, assetType, version)` | **current = highest version; trusted = highest version with `activated_at` set** (≥3 accepted sessions). Clients default to trusted, may request `?includeUnactivated=1` for preview/admin |
| Raw | R2 `raw/` | never deleted by this layer (Phase-3 lifecycle rule is out of scope until reconstruction is routine) |

A new reconstruction never mutates an old one: re-running a cell yields version N+1 in both R2
key and DB row, so a client holding version N keeps a stable URL. "Delete raw N days after an
activated reconstruction exists" remains a deliberately unbuilt Phase-3+ policy.

## 4. Asset streaming

- DB stores `r2_key` only. Clients get `assetUrl: /api/world-model/asset/{assetId}` — a redirect
  endpoint minting a short-lived presigned R2 GET per request (already designed and proven on
  the fusion branch; same-origin path keeps Twin v4 happy and keeps R2 credentials server-side).
- The streamed format is `splat-web` (spz / compressed.ply, ≤ ~15 MB per cell). `pointcloud`/full
  `splat` assets exist in R2 but are not offered through the public manifest — admin/DGX only.
- CDN caching: the redirect endpoint is cacheable by asset id (immutable per version), presign
  TTL ~15 min; revisit only if egress cost appears (R2 egress is free — the reason it was chosen).

## 5. cdx-platform generalization (deliberately thin)

What cdx-platform (chicago-twin, geodata, Cortex graph) needs is exactly the three read
endpoints + the cell-id convention. So the generalization contract is:

1. **Cell-id formula is public**: documented in `docs/CONVENTIONS.md` (`111000`/`82300` scale,
   byte-equality rule) — any consumer can compute ids from lat/lng without calling GCC.
2. **The three endpoints are the API** — versioned JSON shapes, no GCC-internal types leak
   (DTOs live in `shared/` so Kotlin consumers get them for free).
3. Nothing else is promised. No event bus, no cross-platform schema, no shared database access —
   until a second real consumer (beyond Twin v4) exists. This is the YAGNI line: the GCC path
   must work end-to-end first (per the brief).

## 6. What gets built when (after review)

1. **M1 — manifest + asset redirect** (smallest useful slice; unblocks Twin v4 over the
   prototype cell): §2.3 + §4, port/adapt the two proven routes from the fusion branch.
2. **M2 — cells bbox rollup** (§2.1) + admin world-model stats page (the fusion branch has a
   working draft to adapt).
3. **M3 — cell detail** (§2.2), converging the existing map endpoints onto the rollup query.

Each milestone is independently shippable; none blocks the reconstruction pipeline, which
writes storage, not this read layer.

## 7. Open questions for Blake

1. Should unactivated (single-session) reconstructions be visible to end users as "preview"
   quality, or admin-only until activated? (Design assumes admin-only default via the
   `?includeUnactivated` flag.)
2. Does the web map get a 3D layer in M2, or is Twin v4 the only 3D client for now?
3. Where should these endpoints ultimately live — Next.js (today's API home) or the Ktor
   backend (which owns the index tables and is meant to absorb the API over time)? Design
   works in either; M1 is fastest in Next.js since the proven routes are Next.js.

## 8. Addendum (2026-07-11): registration contract + ownership split

Two decisions landed after the original draft; recorded here so the spec
stays the single reference.

### 8.1 World-layer eligibility now comes from the registration contract

PR #149 ships `manifest.geoRefEvents[]` + `arFrameConvention`
(`gravityAndHeading`) from the mobile client, and
`docs/governance/scan-splat-registration-contract.md` is the source of
truth for how a session earns world placement. The DGX side (merged into
the Phase-3 pipeline 2026-07-11):

- `scripts/reconstruction/geo_registration.py` — clock fit, re-pair
  segmentation, accuracy-weighted 2D similarity (scale=1), gates
  (≥3 events, spread, residual ≤ median GPS accuracy).
- `fuse_cell.fuse_sessions` — world path: every world-solved session
  placed by its own transform against one shared ENU origin, ICP-refined;
  local-only sessions are rejected from the merge. Legacy path (no
  geoRefEvents anywhere): GPS-track yaw fit, honest for display, never
  world-eligible.
- Migration 0036 — `scan_sessions.geo_ref_event_count` (indexer),
  `registration_status` / `registration_residual_m` /
  `world_registration` (worker).
- Asset provenance gains `worldEligible`.

### 8.2 Ownership split (Blake, 2026-07-11)

The scans originate in the GCC app, but the DERIVED DATA has two owners:

| Concern | Owner |
|---|---|
| Trash identification, litter classification/annotation, still-frame extraction for messes, close-scanned mess 3D models, block reports, points/rewards | **GCC backend** (this repo) |
| The combined, geographically-registered splat world — the "cleaned world" reconstruction merged across sessions/cells | **CDX World Model** (cdx-platform; today surfaced through cdx-world-twin :7047) |

The CDX World Model is cross-project: it receives world-eligible splats
from GCC scans today, other data collections tomorrow, and extends the
same geographically-true 3D layer to every client project's maps
(Graphene Gangway business maps, contact-page embeds, etc.). Concretely:
the twin's community/world merge consumes only assets whose provenance
says `worldEligible` (with `northAligned` accepted during the transition
while all existing cells are legacy) — GCC-local reconstructions remain
available in GCC's own views regardless.
