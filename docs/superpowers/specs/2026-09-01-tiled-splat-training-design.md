# Tiled splat training + stitch — design note (Train-Splitter pattern)

**Prompted by** §3.4 item 5 of the 2026-09-01 master plan: overlapping
sub-tiles per cell to cut VRAM and address the 2.2× tile-relief mismatch.

## First finding: the 2.2× mismatch does not apply to GCC cell splats

The 2.2× relief mismatch (PRD `docs/PRD_environment-scan-vidmap-reconstruction.md`,
chicago-twin tile-stitch verdict) came from tiles generated **independently
from approximate priors** — each tile solved its own scale, and adjacent
tiles disagreed on vertical relief by 2.2×.

GCC cell training is structurally immune to that failure:

- `gsplat_runner.train_gsplat_for_cell` poses every accepted session's
  frames through its fusion transform into **one metric, north-aligned cell
  frame** (`fuse_cell` ARKit+GPS registration; `geo_registration.py` ENU
  math, anchor lat/lng in `meta.json`).
- Poses are **frozen ARKit** (`--pose-mode frozen`), fit to GPS at scale
  0.973 / 3.4 m median residual (Aug-19 §3.1) — scale is pinned by the
  capture, not solved per tile.
- The LiDAR depth loss (`--depth-weight 0.3`) anchors relief metrically.

So tiling here is a **VRAM / wall-clock / gaussian-budget tool**, and the
stitch problem reduces to appearance seams, not scale seams. Adjacent *cells*
trained separately also share this property (each is metrically anchored to
its own GPS anchor), so cross-cell seams are translation-exact to GPS
accuracy; the residual cross-cell risk is exposure/appearance, already
per-frame compensated (`--exposure-lr`).

## When tiling pays

Current envelope: ~11 m cells (`gridCell` at `CELL_SIZE_METERS = 10`,
cell ids = 1e-4° lat/lng bins), `MAX_TOTAL_FRAMES` budget, 30 k iters,
5 M gaussian cap, ~95 min/cell on the GB10 — **a single cell fits and does
not need tiles.** Tiling becomes worth building when either:

1. **Block-lap megacells**: fusing a whole block face (N adjacent cells,
   one shared frame) to kill even the appearance seams — frames × gaussians
   then exceed what one training run should hold beside a resident
   Lightning seat; or
2. the 5 M gaussian cap starts visibly starving dense cells.

## Design (when triggered)

All in the existing cell frame; no new math.

### 1. Tile planner (`gsplat_runner.py`, ~80 lines)

- Compute the XY AABB of `fusion_result.merged_points`; split into K tiles
  (2×1 or 2×2) with a **20 % overlap band** (≥ 2 m at cell scale).
- Assign each posed frame to every tile whose *expanded* AABB its camera
  frustum intersects (camera position + forward ray against AABB — poses and
  intrinsics are already in hand as `poses.npy` / `intrinsics.npy` inputs).
  Frames in the overlap band land in both tiles — that is the Train-Splitter
  trick: shared supervision across the seam.
- Crop `init_points`/`init_colors` to each tile's expanded AABB.

### 2. Trainer change (`scripts/gsplat_train/train_gsplat.py`, ~30 lines)

- New `--aabb x0 y0 x1 y1` arg: crop init points at load, and at export
  cull gaussians whose means fall outside the *unexpanded* tile AABB plus a
  feather margin. Nothing else changes — same recipe, same depth loss, same
  frozen poses.

### 3. Serial orchestration (`gsplat_runner.py`)

- Train tiles **strictly serially**, each under the existing
  `GpuReservation(...).acquire("reconstruction")` — GPU-serial doctrine
  holds; per-tile checkpoints reuse the existing resume seam
  (`checkpoint_latest.pt` / `training-state.json`) so a preempted megacell
  resumes at tile granularity.

### 4. Stitch (`scripts/gsplat_train/stitch_tiles.py`, new, ~120 lines)

- Concatenate tile PLYs (all already in the same cell frame — no alignment
  step, by construction).
- Ownership in the overlap band: keep each gaussian from the tile whose
  center is nearest (Voronoi ownership on means), with an opacity feather
  over the last ~0.5 m so the handoff has no hard wall.
- Emit one `splat_gsplat.ply`; downstream (`VerifiedCandidate.from_staging`,
  validation renders, evidence gate) is untouched — the stitch happens
  before the candidate seam, so the whole verified pipeline sees one PLY as
  today.

### Validation

- Reuse `render_validation.py` views, plus one **seam-straddling** render per
  tile boundary; gate on the same VERIFIED_* thresholds (temporal flicker /
  floater ratio catch seam artifacts).

## Effort estimate

| piece | effort |
|---|---|
| tile planner + frame assignment | 1 day |
| `--aabb` trainer arg + export cull | 0.5 day |
| stitch + feather | 1 day |
| seam validation views + tests | 0.5-1 day |
| **total** | **~3-3.5 days**, no new dependencies, no GPU cost beyond the training it already replaces |

## Recommendation

Do **not** build now. Land it as the enabling step of the block-lap megacell
milestone (Phase 3 twin work), where it converts "N cells with N seams" into
"one seamless block face" — that is the payoff the Train-Splitter pattern
actually buys us, since scale consistency is already guaranteed by the ARKit
backbone.
