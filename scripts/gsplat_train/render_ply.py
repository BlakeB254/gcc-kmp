"""Renders a trained 3DGS ply from dataset poses — side-by-side with the
ground-truth frames, for eyeballing novel-view quality without a browser."""
import argparse, os
import numpy as np
import torch
from PIL import Image
from plyfile import PlyData

p = argparse.ArgumentParser()
p.add_argument("--ply", required=True)
p.add_argument("--data", required=True)
p.add_argument("--frames", default="10,80,150")
p.add_argument("--out", required=True)
args = p.parse_args()

from gsplat import rasterization

v = PlyData.read(os.path.expanduser(args.ply))["vertex"].data
n = len(v)
means = torch.tensor(np.stack([v["x"], v["y"], v["z"]], 1), dtype=torch.float32, device="cuda")
scales = torch.exp(torch.tensor(np.stack([v[f"scale_{i}"] for i in range(3)], 1), dtype=torch.float32, device="cuda"))
quats = torch.tensor(np.stack([v[f"rot_{i}"] for i in range(4)], 1), dtype=torch.float32, device="cuda")
opac = torch.sigmoid(torch.tensor(np.asarray(v["opacity"]), dtype=torch.float32, device="cuda"))
sh0 = np.stack([v[f"f_dc_{i}"] for i in range(3)], 1)[:, None, :]
n_rest = len([k for k in v.dtype.names if k.startswith("f_rest_")])
K_sh = n_rest // 3
rest = np.stack([v[f"f_rest_{k}"] for k in range(n_rest)], 1).reshape(n, 3, K_sh).transpose(0, 2, 1)
colors = torch.tensor(np.concatenate([sh0, rest], 1), dtype=torch.float32, device="cuda")
sh_degree = int(np.sqrt(K_sh + 1)) - 1
print(f"{n:,} gaussians, SH degree {sh_degree}")

data = os.path.expanduser(args.data)
poses = np.load(os.path.join(data, "poses.npy"))
Ks = np.load(os.path.join(data, "intrinsics.npy"))
idxs = [int(i) for i in args.frames.split(",")]
gt0 = Image.open(os.path.join(data, "frames", f"{idxs[0]:04d}.jpg"))
W, H = gt0.width, gt0.height

rows = []
for i in idxs:
    viewmat = torch.tensor(np.linalg.inv(poses[i]), dtype=torch.float32, device="cuda")[None]
    K = torch.tensor(Ks[i], dtype=torch.float32, device="cuda")[None]
    with torch.no_grad():
        render, _, _ = rasterization(means=means, quats=torch.nn.functional.normalize(quats, dim=-1),
                                     scales=scales, opacities=opac, colors=colors,
                                     viewmats=viewmat, Ks=K, width=W, height=H,
                                     sh_degree=sh_degree, packed=False)
    img = (render[0].clamp(0, 1).cpu().numpy() * 255).astype(np.uint8)
    gt = np.asarray(Image.open(os.path.join(data, "frames", f"{i:04d}.jpg")))
    rows.append(np.concatenate([gt, img], 1))
grid = np.concatenate(rows, 0)
Image.fromarray(grid).resize((grid.shape[1] // 2, grid.shape[0] // 2)).save(os.path.expanduser(args.out), quality=90)
print("wrote", args.out)
