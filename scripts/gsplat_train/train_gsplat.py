"""Trains a 3D Gaussian Splat from an exported ARKit-posed dataset (see
export_gsplat_dataset.py) with gsplat on the GB10 — geometry initialized
from the session's LiDAR cloud, poses trusted as ground truth (never
optimized), output in the same north-aligned metric frame it trained in.

Run:  .venv/bin/python train_gsplat.py --data ~/models/gcc/gsplat/<name> \
          [--iters 4000] [--out splat_gsplat.ply]
"""
from __future__ import annotations

import argparse
import json
import math
import os

import numpy as np
import torch
from PIL import Image


def load_dataset(path: str):
    meta = json.load(open(os.path.join(path, "meta.json")))
    poses = np.load(os.path.join(path, "poses.npy"))        # cam2world (cv)
    Ks = np.load(os.path.join(path, "intrinsics.npy"))
    frames = []
    for i in range(meta["frames"]):
        fp = os.path.join(path, "frames", f"{i:04d}.jpg")
        frames.append(np.asarray(Image.open(fp), dtype=np.float32) / 255.0)
    imgs = torch.from_numpy(np.stack(frames))
    viewmats = torch.from_numpy(np.linalg.inv(poses)).float()   # world2cam
    Ks = torch.from_numpy(Ks).float()
    pts = torch.from_numpy(np.load(os.path.join(path, "init_points.npy")))
    cols = torch.from_numpy(np.load(os.path.join(path, "init_colors.npy")))
    return meta, imgs, viewmats, Ks, pts, cols


def rgb_to_sh0(rgb: torch.Tensor) -> torch.Tensor:
    return (rgb - 0.5) / 0.28209479177387814


def _ssim(img1: torch.Tensor, img2: torch.Tensor, window: int = 11) -> torch.Tensor:
    """SSIM on HWC float images, pure torch (no extra deps)."""
    c = img1.shape[-1]
    coords = torch.arange(window, device=img1.device, dtype=torch.float32) - window // 2
    g = torch.exp(-(coords ** 2) / (2 * 1.5 ** 2))
    g = (g / g.sum())
    kernel = (g[:, None] @ g[None, :]).expand(c, 1, window, window)
    a = img1.permute(2, 0, 1)[None]
    b = img2.permute(2, 0, 1)[None]
    pad = window // 2
    mu1 = torch.nn.functional.conv2d(a, kernel, padding=pad, groups=c)
    mu2 = torch.nn.functional.conv2d(b, kernel, padding=pad, groups=c)
    s1 = torch.nn.functional.conv2d(a * a, kernel, padding=pad, groups=c) - mu1 ** 2
    s2 = torch.nn.functional.conv2d(b * b, kernel, padding=pad, groups=c) - mu2 ** 2
    s12 = torch.nn.functional.conv2d(a * b, kernel, padding=pad, groups=c) - mu1 * mu2
    C1, C2 = 0.01 ** 2, 0.03 ** 2
    ssim = ((2 * mu1 * mu2 + C1) * (2 * s12 + C2)) / ((mu1 ** 2 + mu2 ** 2 + C1) * (s1 + s2 + C2))
    return ssim.mean()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True)
    parser.add_argument("--iters", type=int, default=15000)
    parser.add_argument("--sh-degree", type=int, default=3)
    parser.add_argument("--out", default="splat_gsplat.ply")
    parser.add_argument("--max-init", type=int, default=400_000)
    args = parser.parse_args()

    from gsplat import rasterization
    from gsplat.strategy import DefaultStrategy

    device = "cuda"
    data_dir = os.path.expanduser(args.data)
    meta, imgs, viewmats, Ks, pts, cols = load_dataset(data_dir)
    H, W = imgs.shape[1], imgs.shape[2]
    print(f"{meta['frames']} frames {W}x{H}, init {len(pts):,} LiDAR points")

    if len(pts) > args.max_init:
        sel = torch.randperm(len(pts))[: args.max_init]
        pts, cols = pts[sel], cols[sel]

    n = len(pts)
    params = torch.nn.ParameterDict({
        "means": torch.nn.Parameter(pts.float().to(device)),
        "scales": torch.nn.Parameter(torch.full((n, 3), math.log(0.03), device=device)),
        "quats": torch.nn.Parameter(torch.cat([torch.ones(n, 1), torch.zeros(n, 3)], 1).to(device)),
        "opacities": torch.nn.Parameter(torch.logit(torch.full((n,), 0.7)).to(device)),
        "sh0": torch.nn.Parameter(rgb_to_sh0(cols.float()).unsqueeze(1).to(device)),
        "shN": torch.nn.Parameter(torch.zeros(n, (args.sh_degree + 1) ** 2 - 1, 3, device=device)),
    }).to(device)

    optimizers = {
        "means": torch.optim.Adam([params["means"]], lr=1.6e-4 * 10),
        "scales": torch.optim.Adam([params["scales"]], lr=5e-3),
        "quats": torch.optim.Adam([params["quats"]], lr=1e-3),
        "opacities": torch.optim.Adam([params["opacities"]], lr=5e-2),
        "sh0": torch.optim.Adam([params["sh0"]], lr=2.5e-3),
        "shN": torch.optim.Adam([params["shN"]], lr=2.5e-3 / 20),
    }
    # Means LR decays exponentially to ~1% over the run (standard 3DGS schedule).
    means_sched = torch.optim.lr_scheduler.ExponentialLR(
        optimizers["means"], gamma=0.01 ** (1.0 / args.iters))
    strategy = DefaultStrategy(verbose=False)
    strategy.check_sanity(params, optimizers)
    state = strategy.initialize_state()

    viewmats, Ks, imgs = viewmats.to(device), Ks.to(device), imgs.to(device)
    n_frames = len(imgs)
    for it in range(args.iters):
        i = int(torch.randint(0, n_frames, ()))
        # Progressive SH: unlock a degree every 1000 iters (standard recipe).
        sh_deg = min(it // 1000, args.sh_degree)
        colors = torch.cat([params["sh0"], params["shN"]], 1)
        render, alpha, info = rasterization(
            means=params["means"],
            quats=torch.nn.functional.normalize(params["quats"], dim=-1),
            scales=torch.exp(params["scales"]),
            opacities=torch.sigmoid(params["opacities"]),
            colors=colors,
            viewmats=viewmats[i:i + 1], Ks=Ks[i:i + 1],
            width=W, height=H, sh_degree=sh_deg, packed=False,
        )
        gt = imgs[i]
        l1 = torch.nn.functional.l1_loss(render[0], gt)
        ssim_loss = 1.0 - _ssim(render[0], gt)
        # Scale regularization: streaky first-run gaussians came from
        # unconstrained anisotropy + growth — penalize both.
        sc = torch.exp(params["scales"])
        aniso = (sc.max(-1).values / sc.min(-1).values.clamp_min(1e-6)).clamp_min(8.0).mean() - 8.0
        big = torch.nn.functional.relu(sc.max(-1).values - 0.4).mean()
        loss = 0.8 * l1 + 0.2 * ssim_loss + 0.01 * aniso + 0.1 * big
        strategy.step_pre_backward(params, optimizers, state, it, info)
        loss.backward()
        strategy.step_post_backward(params, optimizers, state, it, info)
        for opt in optimizers.values():
            opt.step()
            opt.zero_grad(set_to_none=True)
        means_sched.step()
        if it % 500 == 0:
            print(f"iter {it}: L1 {l1.item():.4f} ssim {1-ssim_loss.item():.3f} "
                  f"sh{sh_deg} · {len(params['means']):,} gaussians", flush=True)

    # Export standard 3DGS ply (same layout the pipeline consumes).
    from plyfile import PlyData, PlyElement
    means = params["means"].detach().cpu().numpy()
    scales = params["scales"].detach().cpu().numpy()
    quats = torch.nn.functional.normalize(params["quats"], dim=-1).detach().cpu().numpy()
    opac = params["opacities"].detach().cpu().numpy()
    sh0 = params["sh0"].detach().cpu().numpy()[:, 0, :]
    shN = params["shN"].detach().cpu().numpy()  # (n, K, 3)
    n = len(means)
    n_rest = shN.shape[1] * 3
    dtype = ([("x", "f4"), ("y", "f4"), ("z", "f4"),
              ("nx", "f4"), ("ny", "f4"), ("nz", "f4"),
              ("f_dc_0", "f4"), ("f_dc_1", "f4"), ("f_dc_2", "f4")]
             + [(f"f_rest_{k}", "f4") for k in range(n_rest)]
             + [("opacity", "f4"),
              ("scale_0", "f4"), ("scale_1", "f4"), ("scale_2", "f4"),
              ("rot_0", "f4"), ("rot_1", "f4"), ("rot_2", "f4"), ("rot_3", "f4")])
    rec = np.zeros(n, dtype=dtype)
    rec["x"], rec["y"], rec["z"] = means.T
    rec["f_dc_0"], rec["f_dc_1"], rec["f_dc_2"] = sh0.T
    # 3DGS f_rest layout: channel-major (all R coeffs, then G, then B)
    rest = np.transpose(shN, (0, 2, 1)).reshape(n, -1)
    for k in range(n_rest):
        rec[f"f_rest_{k}"] = rest[:, k]
    rec["opacity"] = opac
    rec["scale_0"], rec["scale_1"], rec["scale_2"] = scales.T
    rec["rot_0"], rec["rot_1"], rec["rot_2"], rec["rot_3"] = quats.T
    out_path = os.path.join(data_dir, args.out)
    PlyData([PlyElement.describe(rec, "vertex")]).write(out_path)
    print(f"trained splat: {out_path} ({n:,} gaussians)")


if __name__ == "__main__":
    main()
