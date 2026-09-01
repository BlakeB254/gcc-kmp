# Mapillary pre-seed for GCC block cells — plan (no downloads yet)

**Goal:** GCC map/twin cells that no volunteer has scanned yet get a
"preview-tier" 3D pre-seed from openly licensed street imagery, so the twin
is never empty and field scans land into context. Source recipe:
`~/cdx-platform/docs/research/north-lawndale-splat-sources.md` (Tier 1:
Mapillary ★★★★★).

## Source & license

- **Mapillary**, CC-BY-SA 4.0, Chicago heavily covered; per-frame
  `compass_angle`, `captured_at`, `camera_parameters`, GPS.
- North Lawndale bbox `(-87.732, 41.847, -87.700, 41.867)`; API constraint:
  bbox queries < 0.01° square → tile into 4-9 chunks.
- Token: free at mapillary.com/dashboard → save at
  `~/.config/cdx/mapillary.env` (matches the aiq.env/nim.env pattern).
  **Blake action** — nothing downloads before this exists.

### Attribution obligations (CC-BY-SA is not free-as-in-silent)

Every derived asset must carry, and every rendering surface must show:

1. **Attribution** — "Imagery © Mapillary contributors, CC-BY-SA 4.0" plus
   per-image author where the API returns one; stored on the asset row, not
   just the UI.
2. **ShareAlike** — a splat trained from CC-BY-SA imagery is a derivative:
   the *pre-seed assets themselves* must be distributable under CC-BY-SA.
   Consequence: keep pre-seeds in a **separate provenance class**
   (`source: "mapillary"`, `license: "CC-BY-SA-4.0"` on the
   `reconstruction_assets` row) and never blend Mapillary frames into a
   volunteer-scan training set — a blended splat would drag the whole cell
   asset into ShareAlike. Supersede, don't merge (see lifecycle below).
3. Twin viewer + site cell galleries render the attribution line whenever a
   pre-seed asset is visible (one shared component; same place the
   "unverified preview" badge goes).

## Which pipeline stage consumes it

**WorldMirror, not VidMap.** The Aug-19 P0 ran VidMap on Mapillary bundles
and returned a conditional NO-GO on that footage class (sparse sequences,
features die between frames). WorldMirror is the feed-forward prior/tile
generator and takes exactly what Mapillary provides (images + approximate
poses from GPS+compass) — it is also how `north-lawndale-splat-sources.md`
already routes it (`pipe_splat` → cdx-world-mirror).

```
mapillary_preseed.py harvest (NEW, scripts/reconstruction/)
  ├─ chunked-bbox query (< 0.01° tiles), group by sequence_id
  ├─ bucket frames per GCC cell (same 1e-4° cell ids as gridCell /
  │    r2_store cell math — no new geometry)
  ├─ per cell with ≥ ~12 frames from ≥ 2 headings:
  │    → cdx-world-mirror (:7027, WORLD_MIRROR_URL — the client
  │      scripts/reconstruction/world_mirror_client.py already exists)
  │    → gaussians.ply in the cell frame, anchored at cell center
  ├─ write reconstructed/{cell}/0/ as asset_type "preseed"
  │    (version 0 = pre-scan tier; provenance: source urls, image ids,
  │     authors, license, harvest date)
  └─ Neon reconstruction_assets row (source="mapillary",
       license="CC-BY-SA-4.0") — visible to twin export like any asset
```

GPU note: WorldMirror inference is a GPU job → harvest N cells, run
reconstructions **serially** under GpuReservation, same as every other lane.

## Lifecycle vs. real scans

- Pre-seed is **version 0, preview tier**: rendered with the "unverified /
  preview" treatment, never counted as civic evidence, never feeding
  litter observations.
- The moment a volunteer scan completes for the cell, the normal pipeline
  writes `reconstructed/{cell}/{v≥1}` and the twin export **prefers the
  scan asset**; the pre-seed row stays (cheap, and useful as a before-state)
  but stops rendering by default.
- Raw harvested imagery is cached under a dedicated prefix
  (`preseed-sources/mapillary/…`), never under `raw/` (that namespace is the
  mobile app's) and never mixed with scrubbed volunteer footage.

## Execution order (when green-lit)

1. Blake: Mapillary token → `~/.config/cdx/mapillary.env`.
2. Harvester script + cell bucketing + provenance manifest (~1 day).
3. Pilot: the 4-block K-Town bbox from the research doc
   (`-87.728, 41.860, -87.720, 41.864`) → expect a handful of cells with
   enough multi-heading coverage; 3-5 WorldMirror runs, serial (~1 GPU
   afternoon).
4. `preseed` asset type + license/attribution fields through the
   reconstruction-assets read path (pairs naturally with master-plan Task
   3.2's `GET /api/admin/reconstruction-assets`).
5. Twin/site attribution component + preview badge.

Nothing here blocks, or is blocked by, the recon lane's scan pipeline — it
is purely additive at the asset layer.
