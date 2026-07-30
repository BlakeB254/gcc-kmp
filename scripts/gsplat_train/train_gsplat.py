"""Trains a 3D Gaussian Splat from an exported ARKit-posed dataset (see
export_gsplat_dataset.py) with gsplat on the GB10 — geometry initialized
from the session's LiDAR cloud, output in the same north-aligned metric
frame it trained in.

Quality recipe (validated on real scans 2026-07-27/28, cell 464650:-721828
A/B/C — see docs in PR):
  - antialiased rasterization
  - per-camera SE3 pose refinement: ARKit VIO drifts 2-5cm over a walk and
    every cm becomes blur; frame 0 stays anchored so the geo-registered
    frame is preserved (refined deltas are relative, cm-scale).
  - per-frame affine exposure compensation (iPhone auto-exposure swings
    otherwise wash out ground texture); frame 0 anchored.
  - LiDAR depth supervision: the fused cloud is z-buffered into every view
    as sparse metric depth targets; an L1 term on rendered expected depth
    pins gaussians to sensor geometry. This collapses the along-ray
    ambiguity that produced streak fans in novel views, and turns would-be
    floaters transparent (measured: 5k off-surface survivors vs 39k
    without depth, 852k in the pre-recipe production output).
  - densification caps: depth-mode gradients inflate means2d grads and,
    uncapped, blew one run to 17.6M gaussians by iter 8500.

Run:  .venv/bin/python train_gsplat.py --data ~/models/gcc/gsplat/<name> \
          [--iters 30000] [--out splat_gsplat.ply]
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
    parser.add_argument("--iters", type=int, default=30000)
    parser.add_argument("--sh-degree", type=int, default=3)
    parser.add_argument("--out", default="splat_gsplat.ply")
    parser.add_argument("--max-init", type=int, default=400_000)
    parser.add_argument(
        "--max-gaussians",
        type=int,
        default=5_000_000,
        help="stop densification once the live Gaussian count reaches this ceiling",
    )
    parser.add_argument("--depth-weight", type=float, default=0.3,
                        help="LiDAR depth-loss weight (0 disables)")
    parser.add_argument("--depth-ds", type=int, default=8,
                        help="depth-target grid downsample factor")
    parser.add_argument("--pose-lr", type=float, default=1e-4,
                        help="per-camera pose refinement lr (0 disables)")
    parser.add_argument("--exposure-lr", type=float, default=1e-3,
                        help="per-frame exposure compensation lr (0 disables)")
    parser.add_argument("--warmup", type=int, default=1000,
                        help="iters before pose/exposure optimization engages")
    args = parser.parse_args()

    from gsplat import rasterization
    from gsplat.strategy import DefaultStrategy

    device = "cuda"
    data_dir = os.path.expanduser(args.data)
    meta, imgs, viewmats, Ks, pts, cols = load_dataset(data_dir)
    H, W = imgs.shape[1], imgs.shape[2]
    print(f"{meta['frames']} frames {W}x{H}, init {len(pts):,} LiDAR points")

    full_pts = pts.float()  # full cloud kept for depth targets (pre-subsample)
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
    # grow_grad2d doubled + early refine stop: depth-mode means2d gradients
    # otherwise densify without bound (17.6M gaussians observed uncapped).
    strategy = DefaultStrategy(
        verbose=False, grow_grad2d=4e-4,
        refine_stop_iter=min(9000, max(1000, int(args.iters * 0.3))))
    strategy.check_sanity(params, optimizers)
    state = strategy.initialize_state()

    viewmats, Ks, imgs = viewmats.to(device), Ks.to(device), imgs.to(device)
    n_frames = len(imgs)

    # Sparse metric depth targets: z-buffer the full LiDAR cloud into each
    # view on a coarse grid. Coverage is sparse (only sensor-ranged surfaces)
    # which is exactly right — sky/background stay unconstrained.
    DS = args.depth_ds
    H4, W4 = H // DS, W // DS
    depth_targets = None
    if args.depth_weight > 0:
        fp = full_pts.to(device)
        depth_targets = torch.zeros(n_frames, H4, W4, device=device)
        with torch.no_grad():
            for fi in range(n_frames):
                m = viewmats[fi]
                Xc = fp @ m[:3, :3].T + m[:3, 3]
                z = Xc[:, 2]
                ok = (z > 0.2) & (z < 30.0)
                Xc, z = Xc[ok], z[ok]
                K = Ks[fi]
                u = (K[0, 0] * Xc[:, 0] / z + K[0, 2]) / DS
                v = (K[1, 1] * Xc[:, 1] / z + K[1, 2]) / DS
                inb = (u >= 0) & (u < W4) & (v >= 0) & (v < H4)
                u, v, z = u[inb].long(), v[inb].long(), z[inb]
                buf = torch.full((H4 * W4,), float("inf"), device=device)
                buf.scatter_reduce_(0, v * W4 + u, z, reduce="amin")
                buf[torch.isinf(buf)] = 0.0
                depth_targets[fi] = buf.reshape(H4, W4)
        cover = float((depth_targets > 0).float().mean())
        print(f"depth targets: {n_frames} views at {W4}x{H4}, coverage {cover:.0%}", flush=True)

    # Pose refinement (SE3 delta per camera) + exposure compensation
    # (affine color per frame). Frame 0 anchored for both, so the session's
    # geo-registered frame and global color balance stay fixed.
    pose_deltas = torch.nn.Parameter(torch.zeros(n_frames, 6, device=device))
    exposure = torch.nn.Parameter(torch.zeros(n_frames, 12, device=device))
    pose_opt = torch.optim.Adam([pose_deltas], lr=args.pose_lr) if args.pose_lr > 0 else None
    exp_opt = torch.optim.Adam([exposure], lr=args.exposure_lr) if args.exposure_lr > 0 else None
    eye3 = torch.eye(3, device=device)

    def rodrigues(w):
        theta = w.norm().clamp_min(1e-8)
        k = w / theta
        Kx = torch.zeros(3, 3, device=device)
        Kx[0, 1], Kx[0, 2] = -k[2], k[1]
        Kx[1, 0], Kx[1, 2] = k[2], -k[0]
        Kx[2, 0], Kx[2, 1] = -k[1], k[0]
        return eye3 + torch.sin(theta) * Kx + (1 - torch.cos(theta)) * (Kx @ Kx)

    def adjusted_viewmat(i):
        if i == 0 or pose_opt is None:
            return viewmats[i]
        dR = rodrigues(pose_deltas[i, :3])
        m = viewmats[i]
        out = torch.eye(4, device=device)
        out[:3, :3] = dR @ m[:3, :3]
        out[:3, 3] = dR @ m[:3, 3] + pose_deltas[i, 3:]
        return out

    def apply_exposure(img, i):
        if i == 0 or exp_opt is None:
            return img
        M = eye3 + exposure[i, :9].reshape(3, 3)
        return img @ M.T + exposure[i, 9:]
    for it in range(args.iters):
        # DefaultStrategy has iteration bounds but no population bound. Its
        # depth-supervised growth can make each later iteration progressively
        # slower (production reached 18.8M and timed out at 17.5k/30k).
        # Stop future refine passes as soon as the previous pass crosses the
        # ceiling; optimization continues on the population already learned.
        if (
            len(params["means"]) >= args.max_gaussians
            and it < strategy.refine_stop_iter
        ):
            strategy.refine_stop_iter = it
            print(
                f"gaussian cap reached at iter {it}: "
                f"{len(params['means']):,} >= {args.max_gaussians:,}; "
                "densification stopped",
                flush=True,
            )
        i = int(torch.randint(0, n_frames, ()))
        # Progressive SH: unlock a degree every 1000 iters (standard recipe).
        sh_deg = min(it // 1000, args.sh_degree)
        colors = torch.cat([params["sh0"], params["shN"]], 1)
        refining = it >= args.warmup
        vm = adjusted_viewmat(i)[None] if refining else viewmats[i:i + 1]
        render, alpha, info = rasterization(
            means=params["means"],
            quats=torch.nn.functional.normalize(params["quats"], dim=-1),
            scales=torch.exp(params["scales"]),
            opacities=torch.sigmoid(params["opacities"]),
            colors=colors,
            viewmats=vm, Ks=Ks[i:i + 1],
            width=W, height=H, sh_degree=sh_deg, packed=False,
            rasterize_mode="antialiased",
            render_mode="RGB+ED" if depth_targets is not None else "RGB",
        )
        if depth_targets is not None:
            rgb_out, depth_out = render[0, ..., :3], render[0, ..., 3]
        else:
            rgb_out, depth_out = render[0], None
        out = apply_exposure(rgb_out, i) if refining else rgb_out
        gt = imgs[i]
        l1 = torch.nn.functional.l1_loss(out, gt)
        ssim_loss = 1.0 - _ssim(out, gt)
        # Scale regularization: streaky first-run gaussians came from
        # unconstrained anisotropy + growth — penalize both.
        sc = torch.exp(params["scales"])
        aniso = (sc.max(-1).values / sc.min(-1).values.clamp_min(1e-6)).clamp_min(8.0).mean() - 8.0
        big = torch.nn.functional.relu(sc.max(-1).values - 0.4).mean()
        loss = 0.8 * l1 + 0.2 * ssim_loss + 0.01 * aniso + 0.1 * big
        depth_l1 = None
        if depth_targets is not None:
            tgt = depth_targets[i]
            d4 = torch.nn.functional.avg_pool2d(depth_out[None, None], DS)[0, 0]
            a4 = torch.nn.functional.avg_pool2d(alpha[0].permute(2, 0, 1)[None], DS)[0, 0]
            dvalid = (tgt > 0) & (a4 > 0.5)
            if dvalid.any():
                depth_l1 = (d4[dvalid] - tgt[dvalid]).abs().mean()
                loss = loss + args.depth_weight * depth_l1
        strategy.step_pre_backward(params, optimizers, state, it, info)
        loss.backward()
        strategy.step_post_backward(params, optimizers, state, it, info)
        for opt in optimizers.values():
            opt.step()
            opt.zero_grad(set_to_none=True)
        if refining:
            if pose_opt is not None:
                pose_opt.step()
                pose_opt.zero_grad(set_to_none=True)
            if exp_opt is not None:
                exp_opt.step()
                exp_opt.zero_grad(set_to_none=True)
        means_sched.step()
        if it % 500 == 0:
            with torch.no_grad():
                shift = pose_deltas[:, 3:].norm(dim=1).mean().item() * 100
            dtxt = f" depth {depth_l1.item():.3f}m" if depth_l1 is not None else ""
            print(f"iter {it}: L1 {l1.item():.4f} ssim {1-ssim_loss.item():.3f}{dtxt} "
                  f"sh{sh_deg} · {len(params['means']):,} gaussians "
                  f"· pose-shift {shift:.1f}cm", flush=True)

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
