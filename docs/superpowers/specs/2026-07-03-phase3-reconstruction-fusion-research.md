# Phase 3 Reconstruction/Fusion — Research Findings & Recommended Path

**Date:** 2026-07-03 · **Status:** Research addendum — narrows scope, does not close it

**Relationship to canon:** this is the research spike that
`docs/superpowers/specs/2026-07-02-scan-guidance-world-model-and-rewards-design.md`
§3 explicitly deferred ("timeline is genuinely unknown until the spike concludes").
It answers the two questions that spec left open: what tool does the
segment→align→fuse step, and can this DGX's existing `cdx-world-mirror` service be
reused for it. It does not pick exact registration thresholds or a training
cadence — those remain real open decisions, per §3.3 of the parent spec.

## 1. `cdx-world-mirror` / HY-WorldMirror-2.0 is the wrong tool for cross-session fusion

This service already runs on this DGX (`~/cdx-platform/services/cdx-world-mirror/`,
port 7026) and is architecturally a DUSt3R/VGGT-lineage feed-forward 3D foundation
model — a single transformer forward pass over a stacked image set that jointly
predicts depth, camera pose, and 3DGS attributes. That clears the first bar the
parent spec's Lyra-2.0 rejection also applied (reconstructive, not
generative-from-one-photo) — but it fails on a different, specific ground:

- **No persistent map.** Every `/generate` call normalizes extrinsics relative to
  whichever image happens to be first in that call's input list. There is no
  mechanism to align a new batch of images against a previously-computed
  canonical reconstruction — each call produces one arbitrary, self-contained
  joint solve.
- **No outlier robustness across sessions.** It has no analog to RANSAC/robust
  bundle adjustment for rejecting a badly-registered or fraudulent scan among
  many; a bad session's frames would simply get baked into the joint fit.
- **Prior-injection is conditioning, not registration.** Its `prior_cam_path`/
  `prior_depth_path` inputs bias a *fresh* solve with known values — useful
  context, not a fusion/update mechanism for an existing accumulated map.

**Verdict: do not build Phase 3's alignment step on this service.** It remains a
plausible tool for a *later*, narrower task — dense depth/3DGS reconstruction from
an already-registered, curated set of frames per activated cell — but that's a
downstream reuse, not the fusion step itself.

## 2. Recommended split: Open3D for registration, gsplat for training

Two clearly separable technical problems, with different risk profiles:

| Step | Recommended tool | Why | Risk on this hardware |
|---|---|---|---|
| Per-scan segmentation → GPS-gated registration → outlier rejection → dense fusion (design spec §3.1 steps 1-4) | **Open3D** — RANSAC/FGR global registration for coarse alignment, ICP refinement for fine alignment | Well-trodden, purpose-built for exactly this (point-cloud-to-point-cloud alignment), not a research bet | **Low.** Open3D ships prebuilt ARM64 Linux wheels (confirmed working on this box already, `manylinux_2_27_aarch64`) |
| Gaussian-splat training from the merged, activated point cloud (design spec §3.1 step 4) | **gsplat** (already in use by `cdx-world-mirror`) | Already running in production here — CUDA kernels JIT-compile against this GB10's sm_120 successfully, per the existing splat pipeline's measured benchmarks | **De-risked on this specific box already**, though other generic aarch64+Blackwell users report `gsplat_cuda.so`/sm_120 issues elsewhere (gsplat GitHub #595, #855) — don't generalize "gsplat just works on any Blackwell ARM box," but it demonstrably works on *this* one |

COLMAP/OpenMVG were considered as an alternative for the registration step
(classical SfM+bundle-adjustment, the traditional basis for crowdsourced 3D
recovery) but rejected for now: `pycolmap` has no aarch64 wheel today and would
need a source build against `libcolmap-dev`/`libceres-dev` — real, untested
integration risk for a step Open3D already solves with a prebuilt wheel. Revisit
only if Open3D's registration accuracy proves insufficient in practice.

Feed-forward matching models (DUSt3R/MASt3R/VGGT, and WorldMirror itself) were
also considered as a correspondence-generator for the registration step — active
2026 research direction, evaluated against aerial/photogrammetric blocks in
recent literature — but none of them solve cross-session GPS-gated fusion
out of the box either; this is a legitimate future prototyping direction, not
something to build the Phase 3 pipeline around today.

## 3. What this narrows vs. what's still genuinely open

**Narrowed (no longer a research question):**
- The tool choice for both pipeline halves (Open3D + gsplat), both already
  installed/proven on this hardware.
- Whether `cdx-world-mirror` needs modification for Phase 3 — it doesn't; it's
  out of the fusion critical path entirely.

**Still genuinely open (empirical, not literature-answerable):**
- **Registration accuracy on real GCC data.** Whether Open3D-style registration
  actually converges well on consumer LiDAR/ARKit captures taken months apart,
  under different lighting, by different devices — this needs testing against
  real scan pairs from the app, not further research. Niantic's own public
  material (Wayfarer, Lightship VPS docs) confirms the overall pipeline shape
  (segment → GPS-gate → align → outlier-reject → activation gate) but discloses
  no specific algorithm or activation-count threshold — there is nothing further
  to extract from public sources here.
- **GPS-gating radius and minimum-independent-scan-count for the activation
  gate** — an empirical GCC-specific call, same conclusion the parent spec
  already reached.

## 4. Suggested next concrete step

A small, bounded prototype — not a full pipeline build: take 2-3 real scan
sessions of the same physical GCC location (once Phase 2's R2 ingestion has real
data, or using existing test captures), run Open3D global registration + ICP
between them, and manually assess whether the resulting alignment is usable. This
answers the one real open question (§3, first bullet) cheaply, before committing
to building the full segmentation/GPS-gating/outlier-rejection pipeline around it.

## Sources

[Review of Feed-forward 3D Reconstruction: From DUSt3R to VGGT](https://arxiv.org/abs/2507.08448) ·
[WorldMirror: Universal 3D World Reconstruction with Any-Prior Prompting](https://arxiv.org/pdf/2510.10726) ·
[DUSt3R/MASt3R/VGGT on photogrammetric aerial blocks](https://arxiv.org/abs/2507.14798) ·
[Open3D ARM support](https://www.open3d.org/docs/release/arm.html) ·
[COLMAP changelog](https://colmap.github.io/changelog.html) ·
[gsplat aarch64 issue #595](https://github.com/nerfstudio-project/gsplat/issues/595) ·
[gsplat sm_120 issue #855](https://github.com/nerfstudio-project/gsplat/issues/855) ·
[Niantic Spatial global 3D mapping platform](https://www.geekwire.com/2026/from-pokemon-go-to-physical-ai-niantic-spatial-unveils-its-global-3d-mapping-platform/) ·
[Niantic Lightship VPS docs](https://nianticspatial.com/docs/nsdk/features/lightship_vps/).
