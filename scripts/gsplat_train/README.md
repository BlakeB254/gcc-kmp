# gsplat training from ARKit poses (plan-B splat path)

Trains 3D Gaussian Splats DIRECTLY from the scan sessions' posed frames —
poses are ARKit ground truth (metric, gravity-aligned) pre-rotated to true
north (GPS-track yaw fit), geometry initialized from the session's LiDAR
cloud. The output splat is geo-true by construction: no world-mirror, no
Sim3 fitting, nothing to mis-register. (Adopted 2026-07-11 after forensics
showed world-mirror's generative geometry is internally inconsistent with
its own cameras at street scale — see the reconstruction README.)

## Setup (GB10: torch nightly for sm_121, gsplat compiles its CUDA ext)
    ./setup.sh          # venv + torch nightly (cu130) + gsplat

## Pipeline
    # 1. export a session (uses the reconstruction venv for chunk decoding)
    cd ../reconstruction && .venv/bin/python ../gsplat_train/export_gsplat_dataset.py \
        --cell 464650:-721828 --session env-1783640498732-0cgku8 \
        --out ~/models/gcc/gsplat/nl-0cgku8

    # 2. train (LiDAR-initialized, poses frozen, ~4k iters)
    cd ../gsplat_train && .venv/bin/python train_gsplat.py \
        --data ~/models/gcc/gsplat/nl-0cgku8

    # 3. the output splat_gsplat.ply is in the north-aligned metric session
    #    frame — publish via the normal pipeline / stage into the twin.

## Integration TODO
- fold into recon_worker as the splat producer (replacing world-mirror for
  geo-registered splats; world-mirror stays for meshes + single-image 3D)
- SSIM loss + SH degree >0 for quality; densification tuning
- multi-session cells: train on the union of sessions' frames using the
  fusion transforms (all poses already in one north-aligned cell frame)
