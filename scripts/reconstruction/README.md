# DGX Fusion Pipeline (Task 8) — registration/fusion skeleton

Fuses multiple independent scan sessions of the same physical location
(`locationCellId`) into one merged point cloud, per
`docs/superpowers/specs/2026-07-03-phase3-reconstruction-fusion-research.md`'s
recommended split (Open3D for registration, gsplat for splat training — that
doc's own conclusion, this is its implementation).

## Status: logic implemented, blocked on a real Open3D bug on this hardware

**This pipeline cannot currently run end-to-end on this DGX Spark.** Not a bug
in this code — a confirmed segfault in `open3d==0.18.0` (the ONLY aarch64
Linux wheel that exists; `0.19.0`+ ship no aarch64 tag at all) on this
specific aarch64+Blackwell (GB10) machine, in several of its core
geometry-processing calls:

- `o3d.geometry.KDTreeFlann(...)` / `.search_knn_vector_3d(...)`
- `o3d.pipelines.registration.registration_icp(...)`
- `o3d.geometry.PointCloud.create_from_rgbd_image(...)`

All three segfault with **zero Python traceback**, confirmed via minimal
repros run directly in `.venv` (bisected line-by-line — `o3d.geometry.Image(...)`
construction and `RGBDImage.create_from_color_and_depth(...)` are fine in
isolation; the crash is specifically in the dense nearest-neighbor/projection
math). `OMP_NUM_THREADS=1` doesn't help, ruling out a simple threading race.
Reproduce with:

```bash
cd scripts/reconstruction && source .venv/bin/activate
python3 -c "
import open3d as o3d, numpy as np
pts = np.random.rand(500,3)
pcd = o3d.geometry.PointCloud(); pcd.points = o3d.utility.Vector3dVector(pts)
print('pointcloud ok', flush=True)
tree = o3d.geometry.KDTreeFlann(pcd)   # crashes here
print('kdtree ok', flush=True)
"
```

This matches the class of issue the 2026-07-03 research doc already warned
about for gsplat (aarch64/Blackwell wheel fragility, GitHub #595/#855) — it
just turns out Open3D has its own version of the same problem, previously
undiscovered because the earlier audit only confirmed `import open3d`
succeeds, not that its core functions run. **A library importing cleanly on
this hardware is not evidence its compute paths work here** — re-verify with
the repro above before trusting this pipeline's Open3D calls again, including
after any Open3D version bump.

**Not yet attempted** (real options, none tried due to time — pick this up as
its own task): building Open3D from source for aarch64 with different compile
flags; running just this pipeline stage on a non-ARM host (a small x86 cloud
box, or CI runner) and treating the DGX purely as the R2/Postgres/gsplat-training
tier; watching for a future Open3D release that fixes this (re-test with the
repro first, don't assume).

## What's actually implemented and testable

- `r2_store.py` — lists/downloads session manifests + raw frames from R2 for
  a `locationCellId`, uploads fused outputs to `reconstructed/{cell}/{version}/`.
  Pure I/O, no Open3D dependency — testable and usable regardless of the bug
  above.
- `frame_io.py` — turns one `CapturedFrame` (color JPEG + 16-bit depth PNG +
  pose + intrinsics, per the shared Kotlin model) into an Open3D point cloud
  in that session's own local coordinates. **Blocked by the bug above**
  (`create_from_rgbd_image`).
- `registration.py` — GPS-seeded ICP: since ARKit sessions here use
  gravity+heading world alignment (`ArScanner.kt`), two sessions of the same
  area should share axis orientation, so GPS gives a good initial translation
  guess directly (documented assumption, flagged as unverified against a real
  device in the module's own docstring) and ICP refines the rest. Includes a
  fitness-threshold reject path for badly-registered sessions, per the
  design's "reject outliers" requirement. **Blocked by the bug above**
  (`registration_icp`, `KDTreeFlann`).
- `tests/test_registration.py` — synthetic-data tests (known transforms,
  centroid-recovery checks, a rejection case) that validate the *math* is
  correct. **These currently cannot pass on this machine** due to the Open3D
  bug, not because the logic is wrong — verified the crash is in Open3D
  itself via the isolated repros above, independent of this test file.

## Setup

```bash
cd scripts/reconstruction
uv venv --python 3.11 .venv   # open3d's only aarch64 wheel needs 3.11, not this box's default 3.12
source .venv/bin/activate
uv pip install -r requirements.txt
python3 -m pytest tests/ -v   # currently segfaults, see Status above
```

## Not built yet

- `splat_train.py` (gsplat training from a fused, activated point cloud) —
  genuinely needs real multi-session data to build against, which doesn't
  exist yet (see repo-root design doc `docs/superpowers/specs/2026-07-07-raw-area-capture-and-fusion-pipeline-design.md`
  for the mobile-side capture gap this is downstream of), and gsplat/torch
  aren't installed here yet (deliberately — see `requirements.txt`).
- Activation-gate orchestration (`fuse_cell.py`'s CLI entrypoint tying
  `r2_store` → `frame_io` → `registration` → `reconstructionAssets`
  provenance write) — straightforward to write once the Open3D bug above is
  resolved and there's real frame data to run it against; writing it blind
  against a broken/untested Open3D would just be more unverifiable code.
- A `POST` endpoint for writing `reconstructionAssets` rows — none exists on
  the Next.js side yet (confirmed: schema + `r2.ts` key helpers only, no
  writer). Per this repo's "backend APIs are source of truth" convention,
  this pipeline should call a new admin endpoint rather than write to
  Postgres directly — add that alongside `fuse_cell.py`.
