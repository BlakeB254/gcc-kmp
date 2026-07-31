"""Post-training cleanup for a trained 3DGS ply. Three prunes, all cheap
(numpy + plyfile only — runs in any venv, no torch):

  1. near-transparent gaussians (opacity haze; with depth supervision most
     junk ends up here instead of floating in space)
  2. oversized gaussians (fog sheets)
  3. off-surface floaters: LiDAR-occupancy anchoring — a gaussian survives
     only within `dilate` voxels of the fused cloud. CALIBRATION (real-scan
     tested 2026-07-28): voxel 0.5 / dilate 4 (~2m reach) preserves
     photo-learned geometry beyond sensor range; the first attempt at 0.9m
     reach deleted real content (renders went black).
  4. optional (needs --poses): below-ground crop (ground plane estimated
     from camera height) and horizontal corridor crop. --above-cap is OFF
     by default — it was right for a yard scan at 5m but would amputate
     storefronts/buildings on street cells.

Run:  python clean_splat.py --ply splat.ply --points init_points.npy \
          [--poses poses.npy] --out splat_clean.ply
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
from typing import TYPE_CHECKING

import numpy as np
from plyfile import PlyData, PlyElement

if TYPE_CHECKING:
    from evidence_score import EvidenceResult


POLICY_THRESHOLDS = {
    "conservative": {
        "totalScore": 0.45,
        "directSupport": "verified-two-view-rgb-or-lidar-proximity>=0.5",
    },
    "balanced": {
        "totalScore": 0.60,
        "directSupport": "verified-two-view-rgb-or-lidar-proximity>=0.5",
    },
    "diagnostic": {
        "totalScore": 0.75,
        "directSupport": "verified-two-view-rgb-or-lidar-proximity>=0.8",
    },
}
_POLICY_NAMES = ("conservative", "balanced", "diagnostic")
_HISTOGRAM_EDGES = np.linspace(0.0, 1.0, 6)


@dataclass(frozen=True)
class CleanupReport:
    """Caller-facing summary of immutable cleanup artifacts."""

    path: Path
    counts: dict[str, int]
    source_sha256: str
    output_sha256: dict[str, str]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validated_evidence(evidence: "EvidenceResult", count: int) -> dict[str, np.ndarray]:
    total = np.asarray(evidence.total, dtype=np.float64)
    if total.shape != (count,) or not np.isfinite(total).all():
        raise ValueError("evidence total must be finite and match the PLY vertex count")
    if np.any((total < 0.0) | (total > 1.0)):
        raise ValueError("evidence total must be normalized to [0, 1]")
    components: dict[str, np.ndarray] = {}
    for name, values in evidence.components.items():
        if name in {"total", *_POLICY_NAMES}:
            raise ValueError(f"evidence component name {name!r} is reserved")
        component = np.asarray(values, dtype=np.float64)
        if component.shape != (count,) or not np.isfinite(component).all():
            raise ValueError(f"evidence component {name!r} must match the PLY vertex count")
        if np.any((component < 0.0) | (component > 1.0)):
            raise ValueError(f"evidence component {name!r} must be normalized to [0, 1]")
        components[name] = component
    masks = {
        "conservative": np.asarray(evidence.conservative_mask, dtype=bool),
        "balanced": np.asarray(evidence.balanced_mask, dtype=bool),
        "diagnostic": np.asarray(evidence.diagnostic_mask, dtype=bool),
    }
    if any(mask.shape != (count,) for mask in masks.values()):
        raise ValueError("evidence masks must match the PLY vertex count")
    if np.any(masks["balanced"] & ~masks["conservative"]):
        raise ValueError("balanced evidence mask must be nested inside conservative")
    if np.any(masks["diagnostic"] & ~masks["balanced"]):
        raise ValueError("diagnostic evidence mask must be nested inside balanced")
    return {"total": total, **components, **masks}


def _write_filtered_ply(ply: PlyData, mask: np.ndarray, destination: Path) -> None:
    vertices = ply["vertex"].data[mask]
    filtered = PlyData(
        [PlyElement.describe(vertices, "vertex")],
        text=ply.text,
        byte_order=ply.byte_order,
        comments=list(ply.comments),
        obj_info=list(ply.obj_info),
    )
    filtered.write(destination)


def _histograms(components: dict[str, np.ndarray]) -> dict[str, dict[str, list]]:
    result = {}
    for name, values in sorted(components.items()):
        counts, edges = np.histogram(values, bins=_HISTOGRAM_EDGES)
        result[name] = {
            "edges": [float(value) for value in edges],
            "counts": [int(value) for value in counts],
        }
    return result


def _removed_reasons(
    policy: str,
    mask: np.ndarray,
    total: np.ndarray,
    components: dict[str, np.ndarray],
    policy_thresholds: dict[str, dict],
) -> dict[str, int]:
    removed = ~mask
    reasons = {"policy-mask-rejected": int(removed.sum())}
    total_threshold = policy_thresholds[policy].get("totalScore")
    if total_threshold is not None:
        reasons["below-total-score"] = int(np.sum(removed & (total < total_threshold)))
    view_support = components.get("view_support")
    lidar_proximity = components.get("lidar_proximity")
    if view_support is not None and lidar_proximity is not None:
        lidar_threshold = 0.8 if policy == "diagnostic" else 0.5
        reasons["insufficient-direct-support"] = int(
            np.sum(removed & (view_support < 1.0) & (lidar_proximity < lidar_threshold))
        )
    for name, values in sorted(components.items()):
        reasons[f"component-below-0.5:{name}"] = int(np.sum(removed & (values < 0.5)))
    return reasons


def _publish_immutable(staged: Path, destination: Path) -> None:
    """Publish once; an identical retry is a no-op, changed content is rejected."""
    if destination.exists():
        if _sha256(staged) != _sha256(destination):
            raise FileExistsError(f"immutable cleanup candidate already exists: {destination}")
        return
    try:
        os.link(staged, destination)
    except FileExistsError:
        if _sha256(staged) != _sha256(destination):
            raise FileExistsError(
                f"immutable cleanup candidate already exists: {destination}"
            ) from None


def clean_variants(
    ply_path: str | os.PathLike[str],
    evidence: "EvidenceResult",
    output_dir: str | os.PathLike[str],
    *,
    points_path: str | os.PathLike[str] | None = None,
    poses_path: str | os.PathLike[str] | None = None,
) -> CleanupReport:
    """Write three nested keep-mask variants without modifying the raw PLY.

    ``evidence`` is computed from captured observations by ``evidence_score``;
    cleanup only applies those masks and records their reasoning. Optional
    points/poses paths are provenance inputs and are checksum-bound in the
    report when supplied.
    """
    source = Path(ply_path).resolve()
    destination_dir = Path(output_dir).resolve()
    outputs = {
        policy: destination_dir / f"splat_{policy}.ply" for policy in _POLICY_NAMES
    }
    report_path = destination_dir / "cleanup-report.json"
    protected_destinations = [*outputs.values(), report_path]
    if source in protected_destinations:
        raise ValueError("source PLY cannot be a cleanup output")

    source_sha256 = _sha256(source)
    ply = PlyData.read(source)
    vertices = ply["vertex"].data
    count = len(vertices)
    validated = _validated_evidence(evidence, count)
    policy_thresholds = getattr(evidence, "policy_thresholds", POLICY_THRESHOLDS)
    if set(policy_thresholds) != set(_POLICY_NAMES):
        raise ValueError("policy thresholds must describe all three cleanup policies")
    total = validated.pop("total")
    masks = {policy: validated.pop(policy) for policy in _POLICY_NAMES}
    components = validated
    counts = {"source": count, **{policy: int(mask.sum()) for policy, mask in masks.items()}}

    destination_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".cleanup-", dir=destination_dir) as temporary:
        temporary_dir = Path(temporary)
        staged_outputs = {}
        for policy, mask in masks.items():
            staged = temporary_dir / outputs[policy].name
            _write_filtered_ply(ply, mask, staged)
            staged_outputs[policy] = staged
        output_sha256 = {
            policy: _sha256(path) for policy, path in staged_outputs.items()
        }
        provenance = {}
        for name, path_value in (("points", points_path), ("poses", poses_path)):
            if path_value is not None:
                path = Path(path_value).resolve()
                provenance[name] = {"path": str(path), "sha256": _sha256(path)}
        payload = {
            "schemaVersion": 1,
            "source": {
                "path": str(source),
                "sha256": source_sha256,
                "count": count,
            },
            "provenance": provenance,
            "policyThresholds": policy_thresholds,
            "counts": counts,
            "componentScoreHistograms": _histograms(components),
            "removedReasonCounts": {
                policy: _removed_reasons(
                    policy, masks[policy], total, components, policy_thresholds
                )
                for policy in _POLICY_NAMES
            },
            "outputs": {
                policy: {
                    "path": str(outputs[policy]),
                    "sha256": output_sha256[policy],
                    "count": counts[policy],
                    "publishable": policy == "balanced",
                }
                for policy in _POLICY_NAMES
            },
        }
        staged_report = temporary_dir / report_path.name
        staged_report.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")

        # Validate the entire immutable set before publishing any missing file.
        for policy, destination in outputs.items():
            if destination.exists() and _sha256(staged_outputs[policy]) != _sha256(destination):
                raise FileExistsError(
                    f"immutable cleanup candidate already exists: {destination}"
                )
        if report_path.exists() and _sha256(staged_report) != _sha256(report_path):
            raise FileExistsError(
                f"immutable cleanup candidate report already exists: {report_path}"
            )
        for policy, destination in outputs.items():
            _publish_immutable(staged_outputs[policy], destination)
        _publish_immutable(staged_report, report_path)

    if _sha256(source) != source_sha256:
        raise RuntimeError("source PLY changed during cleanup")
    return CleanupReport(
        path=report_path,
        counts=counts,
        source_sha256=source_sha256,
        output_sha256=output_sha256,
    )


def _legacy_evidence(
    ply_path: str | os.PathLike[str],
    points_path: str | os.PathLike[str],
    *,
    poses_path: str | os.PathLike[str] | None,
    min_opacity: float,
    max_scale: float,
    voxel: float,
    dilate: int,
    cam_height: float,
    below_margin: float,
    above_cap: float,
    corridor: float,
) -> SimpleNamespace:
    """Adapt the captured-point legacy CLI to the evidence-mask interface."""
    ply = PlyData.read(ply_path)
    v = ply["vertex"].data
    n0 = len(v)
    xyz = np.stack([v["x"], v["y"], v["z"]], 1)
    opacity = 1 / (1 + np.exp(-np.asarray(v["opacity"])))
    biggest = np.exp(np.stack([v[f"scale_{i}"] for i in range(3)], 1)).max(1)

    physical = (opacity >= min_opacity) & (biggest <= max_scale)
    relaxed_physical = (opacity >= min_opacity / 2.0) & (biggest <= max_scale * 1.5)
    strict_physical = (opacity >= max(0.10, min_opacity * 2.0)) & (
        biggest <= min(0.25, max_scale * 0.75)
    )
    print(f"clean_splat: opacity<{min_opacity}: {int((opacity < min_opacity).sum()):,}"
          f"  scale>{max_scale}: {int((biggest > max_scale).sum()):,}")

    pts = np.load(points_path)
    vox = set(map(tuple, np.floor(pts / voxel).astype(np.int64)))
    offs = [(dx, dy, dz) for dx in range(-dilate, dilate + 1)
            for dy in range(-dilate, dilate + 1) for dz in range(-dilate, dilate + 1)]
    dil: set[tuple[int, int, int]] = set()
    for o in offs:
        dil.update((a + o[0], b + o[1], c + o[2]) for a, b, c in vox)
    gvox = np.floor(xyz / voxel).astype(np.int64)
    anchored = np.fromiter((tuple(q) in dil for q in gvox), dtype=bool, count=n0)
    print(f"clean_splat: off-surface floaters: {int((~anchored & physical).sum()):,}")
    spatial = anchored.copy()
    corridor_score = np.ones(n0, dtype=np.float64)

    if poses_path:
        cams = np.load(poses_path)[:, :3, 3]  # cam2world translations
        up = 1  # dataset frame: +Y up
        ground = np.median(cams[:, up]) - cam_height
        below = xyz[:, up] < (ground - below_margin)
        spatial &= ~below
        removed = int(below.sum())
        if above_cap > 0:
            above = xyz[:, up] > (ground + above_cap)
            spatial &= ~above
            removed += int(above.sum())
        hor = [a for a in (0, 1, 2) if a != up]
        d2 = np.full(n0, np.inf)
        for c in cams[::4]:
            dd = (xyz[:, hor[0]] - c[hor[0]]) ** 2 + (xyz[:, hor[1]] - c[hor[1]]) ** 2
            np.minimum(d2, dd, out=d2)
        far = d2 > corridor ** 2
        spatial &= ~far
        corridor_score = np.clip(1.0 - np.sqrt(d2) / max(corridor, 1e-12), 0.0, 1.0)
        print(f"clean_splat: ground/corridor crops: {removed + int(far.sum()):,}"
              f" (ground y={ground:.2f})")

    conservative = spatial & relaxed_physical
    balanced = spatial & physical
    diagnostic = balanced & strict_physical
    opacity_score = np.clip((opacity - min_opacity) / max(1.0 - min_opacity, 1e-12), 0.0, 1.0)
    scale_score = np.clip(1.0 - biggest / max(max_scale, 1e-12), 0.0, 1.0)
    total = np.mean(
        np.stack((opacity_score, scale_score, anchored.astype(float), corridor_score)), axis=0
    )
    return SimpleNamespace(
        total=total,
        components={
            "opacity": opacity_score,
            "scale": scale_score,
            "lidar_proximity": anchored.astype(float),
            "view_support": np.zeros(n0, dtype=np.float64),
            "corridor_distance": corridor_score,
        },
        conservative_mask=conservative,
        balanced_mask=balanced,
        diagnostic_mask=diagnostic,
        policy_thresholds={
            "conservative": {
                "minOpacity": min_opacity / 2.0,
                "maxScale": max_scale * 1.5,
                "voxel": voxel,
                "dilate": dilate,
                "directSupport": "captured-lidar-voxel-anchor",
            },
            "balanced": {
                "minOpacity": min_opacity,
                "maxScale": max_scale,
                "voxel": voxel,
                "dilate": dilate,
                "directSupport": "captured-lidar-voxel-anchor",
            },
            "diagnostic": {
                "minOpacity": max(0.10, min_opacity * 2.0),
                "maxScale": min(0.25, max_scale * 0.75),
                "voxel": voxel,
                "dilate": dilate,
                "directSupport": "captured-lidar-voxel-anchor",
            },
        },
    )


def clean(ply_path: str, points_path: str, out_path: str, *,
          poses_path: str | None = None, min_opacity: float = 0.05,
          max_scale: float = 0.35, voxel: float = 0.5, dilate: int = 4,
          cam_height: float = 1.5, below_margin: float = 0.3,
          above_cap: float = 0.0, corridor: float = 15.0) -> tuple[int, int]:
    """Compatibility wrapper returning the balanced ``(kept, total)`` pair."""
    source = Path(ply_path).resolve()
    legacy_output = Path(out_path).resolve()
    if source == legacy_output:
        raise ValueError("source PLY cannot be the legacy cleanup output")
    named_outputs = {
        legacy_output.parent / f"splat_{policy}.ply" for policy in _POLICY_NAMES
    }
    if legacy_output in named_outputs and legacy_output.name != "splat_balanced.ply":
        raise ValueError("legacy output cannot replace a named cleanup policy")
    evidence = _legacy_evidence(
        source,
        points_path,
        poses_path=poses_path,
        min_opacity=min_opacity,
        max_scale=max_scale,
        voxel=voxel,
        dilate=dilate,
        cam_height=cam_height,
        below_margin=below_margin,
        above_cap=above_cap,
        corridor=corridor,
    )
    report = clean_variants(
        source,
        evidence,
        legacy_output.parent,
        points_path=points_path,
        poses_path=poses_path,
    )
    balanced = legacy_output.parent / "splat_balanced.ply"
    if legacy_output != balanced:
        shutil.copyfile(balanced, legacy_output)
    kept, total = report.counts["balanced"], report.counts["source"]
    print(f"clean_splat: {total:,} -> {kept:,} gaussians ({100 * kept / max(1, total):.0f}% kept)")
    return kept, total


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--ply", required=True)
    p.add_argument("--points", required=True, help="init_points.npy from the dataset")
    p.add_argument("--poses", help="poses.npy — enables ground/corridor crops")
    p.add_argument("--out", required=True)
    p.add_argument("--min-opacity", type=float, default=0.05)
    p.add_argument("--max-scale", type=float, default=0.35)
    p.add_argument("--voxel", type=float, default=0.5)
    p.add_argument("--dilate", type=int, default=4)
    p.add_argument("--cam-height", type=float, default=1.5)
    p.add_argument("--below-margin", type=float, default=0.3)
    p.add_argument("--above-cap", type=float, default=0.0,
                   help="crop above ground+N meters (0 = off; unsafe near buildings)")
    p.add_argument("--corridor", type=float, default=15.0)
    args = p.parse_args()
    clean(args.ply, args.points, args.out, poses_path=args.poses,
          min_opacity=args.min_opacity, max_scale=args.max_scale,
          voxel=args.voxel, dilate=args.dilate, cam_height=args.cam_height,
          below_margin=args.below_margin, above_cap=args.above_cap,
          corridor=args.corridor)


if __name__ == "__main__":
    main()
