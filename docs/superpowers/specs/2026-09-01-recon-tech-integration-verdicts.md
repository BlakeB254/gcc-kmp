# Recon-tech integration batch — verdicts (2026-09-01)

Executes the ranked shortlist in
`docs/superpowers/plans/2026-09-01-env-scan-polish-block-lap-world-twin-android.md`
§3.4, against the standing Aug-19 verdicts (ARKit canonical; VidMap
external-footage-only). Per-item verdicts with receipts; companion design
docs are linked per item.

## 1. CoMe splat→mesh — **STOP: non-commercial license**

`r4dl/CoMe` (clone at `~/opensource/CoMe`, code only, no bench run) ships the
**Inria/MPII Gaussian-Splatting license**: "research and/or evaluation
purposes only", commercial use requires prior written consent from Inria
(`LICENSE.md`), and `NOTICE.md` confirms the entire derivative chain
(CoMe ← SOF ← StopThePop ← gaussian-splatting) "remains under the same
research-only usage restrictions". The CUDA rasterizer that computes the
per-gaussian confidence is itself under the NC license, so the technique as
shipped cannot be integrated into GCC's pipeline.

The collision-mesh gap stays real. The clean-room path, when the gap is
prioritized:

- **TSDF fusion of rendered depth** from the trained splat (CoMe's
  `extract_mesh_tsdf.py` is the standard recipe): render depth from training
  poses with **gsplat** (Apache-2.0, already the trainer), integrate into a
  TSDF grid, marching-cubes the mesh. No CoMe code needed; gsplat's
  rasterizer emits depth today (`render_mode="ED"`). Open3D's TSDF module is
  out (segfaults on GB10) — use a NumPy TSDF + scikit-image
  `marching_cubes`, or vdbfusion (MIT) if it builds on aarch64.
- Effort: ~1-2 days inside `scripts/gsplat_train/` as a post-train step,
  fully GPU-serial (renders ride the same GpuReservation as training).

## 2. depth-anything.cpp — **GO (server-side depth for non-LiDAR frames)**

- Repo: `mudler/depth-anything.cpp` (LocalAI team), **MIT**; DA3/DA2 GGUF
  weights at `mudler/depth-anything.cpp-gguf`, **Apache-2.0** (upstream
  ByteDance DA3 checkpoints are Apache-2.0 too). No license constraint, any
  lane.
- Built clean on GB10 aarch64, first try: cmake + `-DDA_BUILD_CLI=ON`,
  CPU-only ggml build → `~/opensource/depth-anything.cpp/build/examples/cli/da3-cli`.
- Bench input: 5 frames extracted (rotated upright) from the largest scrubbed
  GCC chunk in R2 —
  `scrubbed/464635:-721811/env-1785368290677-binw7a/capture/chunk-0.mov`
  (156 MB, 60 s, 3585 frames, 1280×960 scrubbed rendition). No raw touched.
- Results (CPU, 378×504 output, `/usr/bin/time` wall + peak RSS):

  | model | s/frame | peak RAM | depth range across frames |
  |---|---|---|---|
  | DA3METRIC-LARGE f32 (1.3 GB) | 18.4–21.5 s | 1.65 GB | 0.60–26.9 m |
  | DA3-BASE q8_0 (149 MB) | 6.6 s | 388 MB | relative (no metric) |

- Quality: metric ranges are physically plausible per frame (0.8–6 m in a
  close sidewalk pass, up to 26.9 m looking down the street); the rendered
  depth map resolves the parkway tree trunk against the background, keeps a
  smooth ground-plane gradient, and masks sky. Receipt:
  `2026-09-01-da3-frame30-depth.png` (beside this doc) — source frame stays
  in R2 (scrubbed lane), not committed.
- There is no python DA3 baseline installed on this box, so this is an
  absolute bench, not an A/B — which is itself the point: the .cpp port
  needs **no venv, no torch, no GPU seat**, so it can run beside Lightning
  with zero VRAM cost.
- Verdict: **adopt as the server-side depth backend for frames without LiDAR**
  (old devices, Ray-Ban lane) — Aug-19 work order #2 closes. At ~19 s/frame
  CPU on the metric model and ~1 frame/s pipeline sampling, a 60 s chunk
  costs ~19 min of pure CPU — acceptable for a queue that today has zero
  non-LiDAR sessions; revisit a CUDA ggml build (or q8_0 metric quantize via
  `da3-cli quantize`) only when that lane carries real volume.
- Integration point: `fuse_cell.py` depth ingestion — where a chunk has no
  LZFSE LiDAR sidecar, shell out to `da3-cli` per sampled frame
  (`--model depth-anything-metric-large-f32.gguf --pfm`), feed the PFM as the
  depth source with a lower confidence weight. Weights live at
  `~/models/depth-anything-gguf/` (central model repo).

## 3. SAM3 track dedupe — **GO on design; blocked on weights provisioning**

Dry-run passes; real-inference env is not functional (no local checkpoint, no
torch, no `~/opensource/sam3` clone — and the runner correctly refuses to
download `facebook/sam3`). Full integration plan + go/no-go:
`docs/superpowers/specs/2026-09-01-sam3-dedupe-integration-plan.md`.

## 4. Tiled splat training (Train-Splitter pattern) — **design written, build when it pays**

Design note with the existing cell math and a concrete effort estimate:
`docs/superpowers/specs/2026-09-01-tiled-splat-training-design.md`.
Key finding: the 2.2× relief mismatch came from tiles reconstructed with
*independent scale*; GCC cell splats already train in one metric,
north-aligned, ARKit-pinned cell frame, so tiling here is a VRAM/wall-clock
tool, not a scale fix — and single ~11 m cells don't need it yet.

## 5. Mapillary pre-seed — **plan written, no downloads yet**

`docs/superpowers/specs/2026-09-01-mapillary-preseed-plan.md` — chunked-bbox
harvest → per-cell WorldMirror pre-seed assets with CC-BY-SA attribution
carried on every derived asset. Consumes the WorldMirror stage, not VidMap
(Aug-19 P0 on Mapillary bundles was a conditional NO-GO for VidMap footage).

## Sequenced next (not started here)

**WorldMirror A/B on an iOS-sourced cell (§0.3 / Aug-19 work order #1b)** —
waits for the fresh field cell landing tonight; the ARKit-pose evidence
already satisfies its rms gate.

## Integration order recommendation

1. **depth-anything.cpp** — benched, licensed clean, zero GPU cost, small
   seam (`fuse_cell` depth ingestion); unblocks the Ray-Ban/old-device lane.
2. **SAM3 dedupe** — highest data-quality payoff (observation dedupe feeds
   every twin layer), but needs the weights decision from Blake first.
3. **Tiled training** — build the stitch tooling when block-lap megacell
   merges start, not before.
4. **Collision mesh** — clean-room TSDF post-step (not CoMe) when the
   twin/game consumer is ready to eat meshes.
