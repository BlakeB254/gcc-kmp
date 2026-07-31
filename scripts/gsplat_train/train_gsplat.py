"""Trains a 3D Gaussian Splat from an exported ARKit-posed dataset (see
export_gsplat_dataset.py) with gsplat on the GB10 — geometry initialized
from the session's LiDAR cloud, output in the same north-aligned metric
frame it trained in.

Quality recipe (validated on real scans 2026-07-27/28, cell 464650:-721828
A/B/C — see docs in PR):
  - antialiased rasterization
  - frozen ARKit camera poses: verified publication preserves the captured,
    calibrated metric trajectory exactly; no independent frame deltas.
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
from dataclasses import dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import random
from typing import Sequence

import numpy as np
from PIL import Image


TRAINING_RECIPE_REVISION = "verified-frozen-v1"
OPTIMIZER_LEARNING_RATES = {
    "means": 1.6e-3,
    "scales": 5e-3,
    "quats": 1e-3,
    "opacities": 5e-2,
    "sh0": 2.5e-3,
    "shN": 2.5e-3 / 20,
}
MEANS_SCHEDULER_FINAL_FRACTION = 0.01
STRATEGY_GROW_GRAD2D = 4e-4
INITIAL_SCALE_METERS = 0.03
INITIAL_OPACITY = 0.7
SH_DEGREE_INTERVAL = 1000


@dataclass(frozen=True)
class DatasetFingerprints:
    dataset_sha256: str
    frozen_poses_sha256: str
    validation_split_sha256: str


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def dataset_fingerprints(data_dir: str | os.PathLike[str]) -> DatasetFingerprints:
    """Hash exactly the exported evidence and its fixed camera trajectory."""
    root = Path(data_dir)
    relative_paths = [
        Path("meta.json"),
        Path("poses.npy"),
        Path("intrinsics.npy"),
        Path("init_points.npy"),
        Path("init_colors.npy"),
        Path("frame-audit.json"),
        Path("validation-view-spec.json"),
        *sorted(path.relative_to(root) for path in (root / "frames").glob("*.jpg")),
    ]
    digest = hashlib.sha256()
    for relative_path in relative_paths:
        path = root / relative_path
        if not path.is_file():
            raise ValueError(f"verified dataset input is missing: {relative_path}")
        encoded_name = relative_path.as_posix().encode("utf-8")
        digest.update(len(encoded_name).to_bytes(4, "big"))
        digest.update(encoded_name)
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    return DatasetFingerprints(
        dataset_sha256=digest.hexdigest(),
        frozen_poses_sha256=_sha256_file(root / "poses.npy"),
        validation_split_sha256=_sha256_file(root / "validation-view-spec.json"),
    )


def training_config(
    args: argparse.Namespace, *, validation_split_sha256: str | None = None
) -> dict:
    """Return every behavior-affecting value needed to validate recovery."""
    exposure_lr = args.exposure_lr if args.exposure_lr > 0 else None
    refine_stop_iteration = min(9000, max(1000, int(args.iters * 0.3)))
    return {
        "recipeRevision": TRAINING_RECIPE_REVISION,
        "validationSplitSha256": validation_split_sha256,
        "poseMode": args.pose_mode,
        "iterations": args.iters,
        "shDegree": args.sh_degree,
        "shDegreeInterval": SH_DEGREE_INTERVAL,
        "maxInitialGaussians": args.max_init,
        "maxGaussians": args.max_gaussians,
        "depth": {"weight": args.depth_weight, "downsample": args.depth_ds},
        "warmupIterations": args.warmup,
        "checkpointEvery": args.checkpoint_every,
        "optimizerLearningRates": {
            **OPTIMIZER_LEARNING_RATES,
            "exposure": exposure_lr,
        },
        "meansSchedule": {"finalFraction": MEANS_SCHEDULER_FINAL_FRACTION},
        "strategy": {
            "growGrad2d": STRATEGY_GROW_GRAD2D,
            "refineStopIteration": refine_stop_iteration,
        },
        "initialization": {
            "scaleMeters": INITIAL_SCALE_METERS,
            "opacity": INITIAL_OPACITY,
        },
        "rasterization": {
            "mode": "antialiased",
            "packed": False,
            "depthMode": "RGB+ED",
        },
        "loss": {
            "l1Weight": 0.8,
            "ssimWeight": 0.2,
            "anisotropyWeight": 0.01,
            "anisotropyFloor": 8.0,
            "largeScaleWeight": 0.1,
            "largeScaleThresholdMeters": 0.4,
        },
    }


def _training_config_sha256(config: dict) -> str:
    encoded = json.dumps(
        config, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def training_config_sha256(config: dict) -> str:
    return _training_config_sha256(config)


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _atomic_json(path: Path, value: dict) -> None:
    temporary = path.with_name(f"{path.name}.tmp")
    try:
        with temporary.open("w") as output:
            json.dump(value, output, indent=2, sort_keys=True)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        _fsync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def write_checkpoint(
    output_dir: str | os.PathLike[str],
    *,
    iteration: int,
    dataset_sha256: str,
    frozen_poses_sha256: str,
    gaussian_parameters: dict,
    auxiliary_parameters: dict | None = None,
    optimizer_states: dict,
    scheduler_state: dict,
    strategy_state: dict,
    rng_state: dict,
    training_config: dict,
    status: str = "running",
) -> Path:
    """Durably write a numbered checkpoint, latest alias, and recovery metadata."""
    import torch

    root = Path(output_dir)
    root.mkdir(parents=True, exist_ok=True)
    numbered = root / f"checkpoint_{iteration}.pt"
    numbered_tmp = root / f"checkpoint_{iteration}.pt.tmp"
    latest = root / "checkpoint_latest.pt"
    latest_tmp = root / "checkpoint_latest.pt.tmp"
    payload = {
        "iteration": iteration,
        "dataset_sha256": dataset_sha256,
        "frozen_poses_sha256": frozen_poses_sha256,
        "gaussian_parameters": gaussian_parameters,
        "auxiliary_parameters": auxiliary_parameters or {},
        "optimizer_states": optimizer_states,
        "scheduler_state": scheduler_state,
        "strategy_state": strategy_state,
        "rng_state": rng_state,
        "training_config": training_config,
        "training_config_sha256": _training_config_sha256(training_config),
    }
    try:
        with numbered_tmp.open("wb") as output:
            torch.save(payload, output)
            output.flush()
            os.fsync(output.fileno())
        os.replace(numbered_tmp, numbered)
        _fsync_directory(root)
        latest_tmp.unlink(missing_ok=True)
        os.link(numbered, latest_tmp)
        os.replace(latest_tmp, latest)
        _fsync_directory(root)
    finally:
        numbered_tmp.unlink(missing_ok=True)
        latest_tmp.unlink(missing_ok=True)
    _atomic_json(root / "training-state.json", {
        "version": 1,
        "status": status,
        "iteration": iteration,
        "checkpoint": latest.name,
        "numberedCheckpoint": numbered.name,
        "datasetSha256": dataset_sha256,
        "frozenPosesSha256": frozen_poses_sha256,
        "trainingConfig": training_config,
        "trainingConfigSha256": _training_config_sha256(training_config),
        "poseMode": "frozen",
    })
    return latest


def load_checkpoint(path: str | os.PathLike[str]) -> dict:
    """Load recovery state on CPU so the caller controls GPU ownership."""
    import torch

    return torch.load(Path(path), map_location="cpu", weights_only=False)


def checkpoint_training(
    output_dir: str | os.PathLike[str],
    *,
    iteration: int,
    dataset_sha256: str,
    frozen_poses_sha256: str,
    params,
    optimizers: dict,
    scheduler,
    strategy_state: dict,
    auxiliary_parameters: dict | None = None,
    auxiliary_optimizers: dict | None = None,
    training_config: dict,
    status: str = "running",
) -> Path:
    """Capture every mutable training component needed for exact recovery."""
    import torch

    all_optimizers = dict(optimizers)
    all_optimizers.update(auxiliary_optimizers or {})
    return write_checkpoint(
        output_dir,
        iteration=iteration,
        dataset_sha256=dataset_sha256,
        frozen_poses_sha256=frozen_poses_sha256,
        gaussian_parameters=params.state_dict(),
        auxiliary_parameters={
            name: value.detach()
            for name, value in (auxiliary_parameters or {}).items()
        },
        optimizer_states={
            name: optimizer.state_dict()
            for name, optimizer in all_optimizers.items()
            if optimizer is not None
        },
        scheduler_state=scheduler.state_dict(),
        strategy_state=strategy_state,
        rng_state={
            "python": random.getstate(),
            "numpy": np.random.get_state(),
            "torch": torch.get_rng_state(),
            "cuda": (
                torch.cuda.get_rng_state_all()
                if torch.cuda.is_initialized()
                else None
            ),
        },
        training_config=training_config,
        status=status,
    )


def restore_rng_state(rng_state: dict) -> None:
    """Restore CPU RNGs without querying or initializing CUDA."""
    import torch

    random.setstate(rng_state["python"])
    np.random.set_state(rng_state["numpy"])
    torch.set_rng_state(rng_state["torch"])
    cuda_state = rng_state.get("cuda")
    if cuda_state is not None:
        if not torch.cuda.is_initialized():
            raise RuntimeError("cannot restore CUDA RNG before the training device exists")
        torch.cuda.set_rng_state_all(cuda_state)


def checkpoint_iterations(start: int, total: int, every: int) -> list[int]:
    """Return recovery boundaries after ``start``, including the final boundary."""
    if every <= 0:
        raise ValueError("checkpoint interval must be positive")
    if total <= start:
        return []
    first = ((start // every) + 1) * every
    iterations = list(range(first, total + 1, every))
    if not iterations or iterations[-1] != total:
        iterations.append(total)
    return iterations


def validate_checkpoint_payload(
    checkpoint: dict,
    *,
    dataset_sha256: str,
    frozen_poses_sha256: str,
    training_config_sha256: str | None = None,
) -> None:
    """Reject a checkpoint whose serialized identity disagrees with its inputs."""
    if checkpoint.get("dataset_sha256") != dataset_sha256:
        raise ValueError("checkpoint dataset fingerprint does not match exported inputs")
    if checkpoint.get("frozen_poses_sha256") != frozen_poses_sha256:
        raise ValueError("checkpoint frozen poses checksum does not match exported trajectory")
    if training_config_sha256 is not None:
        config = checkpoint.get("training_config")
        actual = (
            _training_config_sha256(config)
            if isinstance(config, dict)
            else None
        )
        if (
            actual != training_config_sha256
            or checkpoint.get("training_config_sha256") != actual
        ):
            raise ValueError("checkpoint training configuration does not match requested recipe")


def _to_device(value, device: str):
    """Recursively move strategy state tensors after a CPU checkpoint load."""
    import torch

    if isinstance(value, torch.Tensor):
        return value.to(device)
    if isinstance(value, dict):
        return {key: _to_device(item, device) for key, item in value.items()}
    if isinstance(value, list):
        return [_to_device(item, device) for item in value]
    if isinstance(value, tuple):
        return tuple(_to_device(item, device) for item in value)
    return value


def build_parser() -> argparse.ArgumentParser:
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
    parser.add_argument("--pose-mode", choices=("frozen", "smooth-trajectory"), default="frozen")
    parser.add_argument("--checkpoint-every", type=int, default=1000)
    parser.add_argument("--resume")
    parser.add_argument("--exposure-lr", type=float, default=1e-3,
                        help="per-frame exposure compensation lr (0 disables)")
    parser.add_argument("--warmup", type=int, default=1000,
                        help="iters before exposure optimization engages")
    return parser


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.pose_mode == "smooth-trajectory":
        parser.error(
            "smooth-trajectory is unavailable until an audited trajectory "
            "representation is supplied; verified training requires frozen poses"
        )
    if args.checkpoint_every <= 0:
        parser.error("--checkpoint-every must be positive")
    return args


def validate_resume(
    resume: str | os.PathLike[str],
    *,
    dataset_sha256: str,
    frozen_poses_sha256: str | None = None,
    training_config_sha256: str | None = None,
) -> Path:
    """Validate lightweight recovery metadata before importing PyTorch."""
    resume_path = Path(resume)
    state_path = (
        resume_path / "training-state.json"
        if resume_path.is_dir()
        else resume_path.parent / "training-state.json"
    )
    if not state_path.is_file():
        raise ValueError(f"resume training state is missing: {state_path}")
    state = json.loads(state_path.read_text())
    if state.get("datasetSha256") != dataset_sha256:
        raise ValueError("resume dataset fingerprint does not match exported inputs")
    if (
        frozen_poses_sha256 is not None
        and state.get("frozenPosesSha256") != frozen_poses_sha256
    ):
        raise ValueError("resume frozen poses checksum does not match exported trajectory")
    if training_config_sha256 is not None:
        state_config = state.get("trainingConfig")
        actual = (
            _training_config_sha256(state_config)
            if isinstance(state_config, dict)
            else None
        )
        if (
            actual != training_config_sha256
            or state.get("trainingConfigSha256") != actual
        ):
            raise ValueError("resume training configuration does not match requested recipe")
    checkpoint = resume_path if resume_path.is_file() else resume_path / state["checkpoint"]
    if not checkpoint.is_file():
        raise ValueError(f"resume checkpoint is missing: {checkpoint}")
    return checkpoint


def _strict_split_indices(value, *, name: str, frame_count: int) -> tuple[int, ...]:
    if not isinstance(value, list):
        raise ValueError(f"validation {name} must be an index list")
    indices: list[int] = []
    for raw_index in value:
        if isinstance(raw_index, bool) or not isinstance(raw_index, int):
            raise ValueError(f"validation {name} contains an invalid index")
        if raw_index < 0 or raw_index >= frame_count:
            raise ValueError(f"validation {name} index out of range: {raw_index}")
        if raw_index in indices:
            raise ValueError(f"validation {name} contains duplicate index: {raw_index}")
        indices.append(raw_index)
    return tuple(indices)


def load_training_frame_indices(
    data_dir: str | os.PathLike[str], *, frame_count: int
) -> tuple[int, ...]:
    """Load the immutable pre-training split and return only trainable frames."""
    split_path = Path(data_dir) / "validation-view-spec.json"
    if not split_path.is_file():
        raise ValueError("verified dataset validation split is missing")
    try:
        document = json.loads(split_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("verified dataset validation split is invalid") from error
    if not isinstance(document, dict):
        raise ValueError("verified dataset validation split is invalid")
    recorded = _strict_split_indices(
        document.get("recorded"), name="recorded", frame_count=frame_count
    )
    withheld = _strict_split_indices(
        document.get("withheld"), name="withheld", frame_count=frame_count
    )
    if not recorded:
        raise ValueError("validation split leaves no training frames")
    if set(recorded).intersection(withheld) or set(recorded).union(withheld) != set(
        range(frame_count)
    ):
        raise ValueError("recorded and withheld must be a disjoint complete partition")
    return recorded


def training_frame_index(training_indices: Sequence[int], sample_offset: int) -> int:
    """Map a random training-slot offset through the persisted recorded partition."""
    if not training_indices:
        raise ValueError("training indices must not be empty")
    if isinstance(sample_offset, bool) or not isinstance(sample_offset, int):
        raise ValueError("sample offset must be an integer")
    return int(training_indices[sample_offset % len(training_indices)])


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
    args = parse_args()

    device = "cuda"
    data_dir = os.path.expanduser(args.data)
    fingerprints = dataset_fingerprints(data_dir)
    with open(os.path.join(data_dir, "meta.json"), encoding="utf-8") as stream:
        frame_count = json.load(stream)["frames"]
    training_indices = load_training_frame_indices(data_dir, frame_count=frame_count)
    config = training_config(
        args,
        validation_split_sha256=fingerprints.validation_split_sha256,
    )
    config_sha256 = training_config_sha256(config)
    resume_path = None
    if args.resume:
        resume_path = validate_resume(
            args.resume,
            dataset_sha256=fingerprints.dataset_sha256,
            frozen_poses_sha256=fingerprints.frozen_poses_sha256,
            training_config_sha256=config_sha256,
        )

    global torch
    import torch

    from gsplat import rasterization
    from gsplat.strategy import DefaultStrategy

    meta, imgs, viewmats, Ks, pts, cols = load_dataset(data_dir)
    H, W = imgs.shape[1], imgs.shape[2]
    print(
        f"{meta['frames']} frames ({len(training_indices)} training) "
        f"{W}x{H}, init {len(pts):,} LiDAR points"
    )

    full_pts = pts.float()  # full cloud kept for depth targets (pre-subsample)
    checkpoint = load_checkpoint(resume_path) if resume_path is not None else None
    if checkpoint is not None:
        validate_checkpoint_payload(
            checkpoint,
            dataset_sha256=fingerprints.dataset_sha256,
            frozen_poses_sha256=fingerprints.frozen_poses_sha256,
            training_config_sha256=config_sha256,
        )
        params = torch.nn.ParameterDict({
            name: torch.nn.Parameter(value.to(device))
            for name, value in checkpoint["gaussian_parameters"].items()
        })
    else:
        if len(pts) > config["maxInitialGaussians"]:
            sel = torch.randperm(len(pts))[: config["maxInitialGaussians"]]
            pts, cols = pts[sel], cols[sel]
        n = len(pts)
        params = torch.nn.ParameterDict({
            "means": torch.nn.Parameter(pts.float().to(device)),
            "scales": torch.nn.Parameter(torch.full(
                (n, 3), math.log(INITIAL_SCALE_METERS), device=device
            )),
            "quats": torch.nn.Parameter(torch.cat([torch.ones(n, 1), torch.zeros(n, 3)], 1).to(device)),
            "opacities": torch.nn.Parameter(torch.logit(
                torch.full((n,), INITIAL_OPACITY)
            ).to(device)),
            "sh0": torch.nn.Parameter(rgb_to_sh0(cols.float()).unsqueeze(1).to(device)),
            "shN": torch.nn.Parameter(torch.zeros(
                n, (config["shDegree"] + 1) ** 2 - 1, 3, device=device
            )),
        }).to(device)

    optimizers = {
        name: torch.optim.Adam([params[name]], lr=OPTIMIZER_LEARNING_RATES[name])
        for name in OPTIMIZER_LEARNING_RATES
    }
    # Means LR decays exponentially to ~1% over the run (standard 3DGS schedule).
    means_sched = torch.optim.lr_scheduler.ExponentialLR(
        optimizers["means"],
        gamma=MEANS_SCHEDULER_FINAL_FRACTION ** (1.0 / args.iters),
    )
    # grow_grad2d doubled + early refine stop: depth-mode means2d gradients
    # otherwise densify without bound (17.6M gaussians observed uncapped).
    strategy = DefaultStrategy(
        verbose=False,
        grow_grad2d=STRATEGY_GROW_GRAD2D,
        refine_stop_iter=config["strategy"]["refineStopIteration"],
    )
    strategy.check_sanity(params, optimizers)
    if checkpoint is None:
        state = strategy.initialize_state()
    else:
        for name, optimizer in optimizers.items():
            optimizer.load_state_dict(checkpoint["optimizer_states"][name])
        means_sched.load_state_dict(checkpoint["scheduler_state"])
        saved_strategy = checkpoint["strategy_state"]
        state = _to_device(saved_strategy["state"], device)
        strategy.refine_stop_iter = int(saved_strategy["refine_stop_iter"])

    viewmats, Ks, imgs = viewmats.to(device), Ks.to(device), imgs.to(device)
    n_frames = len(imgs)

    # Sparse metric depth targets: z-buffer the full LiDAR cloud into each
    # view on a coarse grid. Coverage is sparse (only sensor-ranged surfaces)
    # which is exactly right — sky/background stay unconstrained.
    DS = config["depth"]["downsample"]
    H4, W4 = H // DS, W // DS
    depth_targets = None
    if config["depth"]["weight"] > 0:
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

    # ARKit view matrices are immutable. Per-frame affine exposure is the only
    # camera-indexed learned quantity and cannot alter publication geometry.
    if checkpoint is None:
        exposure = torch.nn.Parameter(torch.zeros(n_frames, 12, device=device))
    else:
        exposure = torch.nn.Parameter(
            checkpoint["auxiliary_parameters"]["exposure"].to(device)
        )
    exposure_lr = config["optimizerLearningRates"]["exposure"]
    exp_opt = (
        torch.optim.Adam([exposure], lr=exposure_lr)
        if exposure_lr is not None
        else None
    )
    eye3 = torch.eye(3, device=device)

    def apply_exposure(img, i):
        if i == 0 or exp_opt is None:
            return img
        M = eye3 + exposure[i, :9].reshape(3, 3)
        return img @ M.T + exposure[i, 9:]
    start_iteration = int(checkpoint["iteration"]) if checkpoint is not None else 0
    if start_iteration > args.iters:
        raise ValueError(
            f"resume iteration {start_iteration} exceeds requested iterations {args.iters}"
        )
    if checkpoint is not None:
        if exp_opt is not None:
            exp_opt.load_state_dict(checkpoint["optimizer_states"]["exposure"])
        restore_rng_state(checkpoint["rng_state"])
    scheduled_checkpoints = set(checkpoint_iterations(
        start_iteration, config["iterations"], config["checkpointEvery"]
    ))
    loss_config = config["loss"]
    raster_config = config["rasterization"]
    for it in range(start_iteration, args.iters):
        # DefaultStrategy has iteration bounds but no population bound. Its
        # depth-supervised growth can make each later iteration progressively
        # slower (production reached 18.8M and timed out at 17.5k/30k).
        # Stop future refine passes as soon as the previous pass crosses the
        # ceiling; optimization continues on the population already learned.
        if (
            len(params["means"]) >= config["maxGaussians"]
            and it < strategy.refine_stop_iter
        ):
            strategy.refine_stop_iter = it
            print(
                f"gaussian cap reached at iter {it}: "
                f"{len(params['means']):,} >= {config['maxGaussians']:,}; "
                "densification stopped",
                flush=True,
            )
        sample_offset = int(torch.randint(0, len(training_indices), ()))
        i = training_frame_index(training_indices, sample_offset)
        # Progressive SH: unlock a degree every 1000 iters (standard recipe).
        sh_deg = min(it // SH_DEGREE_INTERVAL, config["shDegree"])
        colors = torch.cat([params["sh0"], params["shN"]], 1)
        refining = it >= config["warmupIterations"]
        render, alpha, info = rasterization(
            means=params["means"],
            quats=torch.nn.functional.normalize(params["quats"], dim=-1),
            scales=torch.exp(params["scales"]),
            opacities=torch.sigmoid(params["opacities"]),
            colors=colors,
            viewmats=viewmats[i:i + 1], Ks=Ks[i:i + 1],
            width=W, height=H, sh_degree=sh_deg, packed=raster_config["packed"],
            rasterize_mode=raster_config["mode"],
            render_mode=raster_config["depthMode"] if depth_targets is not None else "RGB",
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
        anisotropy_floor = loss_config["anisotropyFloor"]
        aniso = (
            sc.max(-1).values / sc.min(-1).values.clamp_min(1e-6)
        ).clamp_min(anisotropy_floor).mean() - anisotropy_floor
        big = torch.nn.functional.relu(
            sc.max(-1).values - loss_config["largeScaleThresholdMeters"]
        ).mean()
        loss = (
            loss_config["l1Weight"] * l1
            + loss_config["ssimWeight"] * ssim_loss
            + loss_config["anisotropyWeight"] * aniso
            + loss_config["largeScaleWeight"] * big
        )
        depth_l1 = None
        if depth_targets is not None:
            tgt = depth_targets[i]
            d4 = torch.nn.functional.avg_pool2d(depth_out[None, None], DS)[0, 0]
            a4 = torch.nn.functional.avg_pool2d(alpha[0].permute(2, 0, 1)[None], DS)[0, 0]
            dvalid = (tgt > 0) & (a4 > 0.5)
            if dvalid.any():
                depth_l1 = (d4[dvalid] - tgt[dvalid]).abs().mean()
                loss = loss + config["depth"]["weight"] * depth_l1
        strategy.step_pre_backward(params, optimizers, state, it, info)
        loss.backward()
        strategy.step_post_backward(params, optimizers, state, it, info)
        for opt in optimizers.values():
            opt.step()
            opt.zero_grad(set_to_none=True)
        if refining:
            if exp_opt is not None:
                exp_opt.step()
                exp_opt.zero_grad(set_to_none=True)
        means_sched.step()
        completed_iteration = it + 1
        if completed_iteration in scheduled_checkpoints and completed_iteration < args.iters:
            checkpoint_training(
                data_dir,
                iteration=completed_iteration,
                dataset_sha256=fingerprints.dataset_sha256,
                frozen_poses_sha256=fingerprints.frozen_poses_sha256,
                params=params,
                optimizers=optimizers,
                scheduler=means_sched,
                strategy_state={
                    "state": state,
                    "refine_stop_iter": strategy.refine_stop_iter,
                },
                auxiliary_parameters={"exposure": exposure},
                auxiliary_optimizers={"exposure": exp_opt},
                training_config=config,
            )
        if it % 500 == 0:
            dtxt = f" depth {depth_l1.item():.3f}m" if depth_l1 is not None else ""
            print(f"iter {it}: L1 {l1.item():.4f} ssim {1-ssim_loss.item():.3f}{dtxt} "
                  f"sh{sh_deg} · {len(params['means']):,} gaussians "
                  "· poses frozen", flush=True)

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
    checkpoint_training(
        data_dir,
        iteration=args.iters,
        dataset_sha256=fingerprints.dataset_sha256,
        frozen_poses_sha256=fingerprints.frozen_poses_sha256,
        params=params,
        optimizers=optimizers,
        scheduler=means_sched,
        strategy_state={
            "state": state,
            "refine_stop_iter": strategy.refine_stop_iter,
        },
        auxiliary_parameters={"exposure": exposure},
        auxiliary_optimizers={"exposure": exp_opt},
        training_config=config,
        status="completed",
    )
    print(f"trained splat: {out_path} ({n:,} gaussians)")


if __name__ == "__main__":
    main()
