# VidMap P0 — outdoor walk pose backbone

Civic lane only. VidMap (ETH CVG, Apache-2.0, [arXiv 2607.27194](https://arxiv.org/abs/2607.27194), staged at `~/opensource/vidmap`) recovers metric cameras + a sparse COLMAP map from a walk video. SuperSplat / WorldMirror stay the recon/editor. VidMap does **not** produce meshes.

PRD: [`docs/PRD_environment-scan-vidmap-reconstruction.md`](../../docs/PRD_environment-scan-vidmap-reconstruction.md).

This spike is the P0 runner + P1 handoff contract. It does **not** install the ~9GB checkpoints or run the model.

## Output contract

For a walk bundle directory:

```
<bundle>/
  rec/vidmap/
    colmap/            cameras.txt + images.txt + points3D.txt
                       (or .bin if a real VidMap run wrote binary)
    poses_gps.json     Sim3 COLMAP → EPSG:4978 ECEF (EPSG:4326 geodetic);
                       `transform` is null with a reason if there is no GPS
    quality.json       keyframes, track-length summary, loop_closure, go / nogo
    prior_camera.json  WorldMirror `extrinsics[]` / `intrinsics[]` (c2w OpenCV)
```

WorldMirror handoff: prefer `rec/vidmap/prior_camera.json` when it contains cameras, else the bundle's existing approximate `prior_camera.json`.

Live `recon_worker` / `fuse_cell` session manifests come from R2 and do not know the local bundle path. Set `VIDMAP_BUNDLE_ROOT` to a directory of `<sessionId>/` walk bundles so `attach_vidmap_priors()` stamps `bundleDir` + `priorCamPath`.

- `SPLAT_ENGINE=world-mirror` (or HTTP `/generate`): VidMap `prior_camera.json` is uploaded as `prior_cam` and passed through to HY-WorldMirror.
- default `gsplat`: ARKit poses when the session has mov+jsonl chunks. If those are missing (civic walk bundle), `gsplat_runner` loads VidMap COLMAP + GPS Sim3 and maps cameras into the cell ENU frame.

```bash
python world_mirror_client.py --bundle /path/to/bundle \
  --input_path /path/to/bundle/frames \
  --output_path /tmp/wm-out \
  --print-command
```

That prints `python -m hyworld2.worldrecon.pipeline ... --prior_cam_path <resolved>` and does not start WorldMirror.

## Dry-run (default — no GPU, no weights)

```bash
cd scripts/reconstruction
source .venv/bin/activate
python vidmap_runner.py /path/to/verified_outdoor_route_bundle
# or: python vidmap_runner.py /path/to/bundle --dry-run
python -m pytest tests/test_vidmap_runner.py -v
```

If `VIDMAP_BIN` / `VIDMAP_REPO` are unset, `quality.json` is `status=not_run`, `reason=vidmap_not_installed`.

Privacy: bundles whose path contains `rayban_wwgo3126_douglass_park` are refused (quarantined indoor residential, 2026-08-10).

## Real P0 later (serial GPU, one job)

Pick a **verified-outdoor** `route_*` walk bundle. Do **not** start Comfy, WorldMirror inference, or a second GPU job.

```bash
export VIDMAP_REPO=$HOME/opensource/vidmap
export VIDMAP_BIN="$HOME/opensource/vidmap/.venv/bin/python -m vidmap.run"
# optional: GCC_GPU_LOCK_PATH=/tmp/gcc-dgx-gpu.lock  (default)
python vidmap_runner.py /path/to/route_*_bundle --run
```

`--run` takes the shared `gpu_admission.py` lock as owner `vidmap` **before** the subprocess. One walk at a time.

Then compare `rec/vidmap/prior_camera.json` against the bundle's approximate prior and, **only when the GPU is free**, run WorldMirror both ways (`--prior_cam_path` from VidMap vs approx) and eyeball the splat delta. That GO/NO-GO is still outstanding.

## License note (before any client-facing use)

VidMap itself is Apache-2.0. Re-check `~/opensource/vidmap/third_party` before shipping anything a client sees:

| Vendor | Tree | License on disk (2026-08-16) |
|---|---|---|
| RoMaV2 | `third_party/RoMaV2` | MIT |
| Depth Anything 3 | `third_party/Depth-Anything-3` | Apache-2.0 (code; **re-check weight terms**) |
| MegaLoc | `third_party/MegaLoc` | MIT |

Weight / dataset terms can differ from the code LICENSE. Do not put VidMap output on a public walk tile until that check is signed off.
