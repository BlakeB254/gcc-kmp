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
import sys
import tempfile
from types import SimpleNamespace
from typing import TYPE_CHECKING

import numpy as np
from plyfile import PlyData, PlyElement

_RECONSTRUCTION_DIR = Path(__file__).resolve().parents[1] / "reconstruction"
if str(_RECONSTRUCTION_DIR) not in sys.path:
    sys.path.insert(0, str(_RECONSTRUCTION_DIR))

from verified_evidence import (
    PlySnapshot,
    candidate_source_checksums,
    load_verified_evidence_artifact,
    read_ply_snapshot,
)
from cleanup_policy import (
    POLICY_NAMES as _POLICY_NAMES,
    POLICY_THRESHOLDS,
    build_policy_thresholds,
    evaluate_cleanup,
    validate_policy_thresholds,
)

if TYPE_CHECKING:
    from evidence_score import EvidenceResult
    from verified_evidence import VerifiedEvidenceArtifact


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
    cam_height: float = 1.5,
    below_margin: float = 0.3,
    above_cap: float = 0.0,
    corridor: float = 15.0,
    verified_evidence: "VerifiedEvidenceArtifact | None" = None,
    source_snapshot: PlySnapshot | None = None,
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

    snapshot = source_snapshot or read_ply_snapshot(source)
    if snapshot.path != source:
        raise ValueError("source PLY snapshot path does not match cleanup source")
    source_sha256 = snapshot.sha256
    ply = snapshot.ply
    vertices = snapshot.vertices
    count = len(vertices)
    vertex_order_sha256 = snapshot.vertex_order_sha256
    if evidence.source_vertex_sha256 != vertex_order_sha256:
        raise ValueError("evidence vertex order fingerprint does not match source PLY")
    if verified_evidence is not None:
        if (
            verified_evidence.raw_ply_sha256 != source_sha256
            or verified_evidence.vertex_order_sha256 != vertex_order_sha256
        ):
            raise ValueError("verified evidence artifact does not match source PLY")
        for name in _POLICY_NAMES:
            if not np.array_equal(
                getattr(evidence, f"{name}_mask"),
                getattr(verified_evidence.result, f"{name}_mask"),
            ):
                raise ValueError("cleanup evidence differs from verified evidence artifact")
    base_thresholds = getattr(evidence, "policy_thresholds", POLICY_THRESHOLDS)
    policy_thresholds = validate_policy_thresholds(
        build_policy_thresholds(
            base_thresholds,
            cam_height=cam_height,
            below_margin=below_margin,
            above_cap=above_cap,
            corridor=corridor,
        ),
        require_verified_base=False,
    )
    evaluation = evaluate_cleanup(
        vertices,
        evidence,
        policy_thresholds,
        poses=np.load(poses_path) if poses_path is not None else None,
    )
    masks = evaluation.masks
    counts = evaluation.counts

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
            "generationId": (
                verified_evidence.generation_id if verified_evidence is not None else None
            ),
            "source": {
                "path": str(source),
                "sha256": source_sha256,
                "count": count,
            },
            "provenance": provenance,
            "policyThresholds": policy_thresholds,
            "counts": counts,
            "componentScoreHistograms": evaluation.histograms,
            "removedReasonCounts": evaluation.removed_reason_counts,
            "outputs": {
                policy: {
                    "path": str(outputs[policy]),
                    "sha256": output_sha256[policy],
                    "count": counts[policy],
                    "publishable": policy == "balanced",
                }
                for policy in _POLICY_NAMES
            },
            "verifiedEvidence": (
                {
                    "path": str(verified_evidence.path),
                    "sha256": verified_evidence.sha256,
                    "schemaVersion": verified_evidence.schema_version,
                    "rawPlySha256": verified_evidence.raw_ply_sha256,
                    "vertexOrderSha256": verified_evidence.vertex_order_sha256,
                    "sourceChecksums": verified_evidence.source_checksums,
                    "provenance": verified_evidence.provenance,
                }
                if verified_evidence is not None
                else None
            ),
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
    source_snapshot: PlySnapshot | None = None,
) -> SimpleNamespace:
    """Adapt the captured-point legacy CLI to the evidence-mask interface."""
    snapshot = source_snapshot or read_ply_snapshot(ply_path)
    v = snapshot.vertices
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
        source_vertex_sha256=snapshot.vertex_order_sha256,
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
    if legacy_output == legacy_output.parent / "cleanup-report.json":
        raise ValueError("legacy output cannot replace the cleanup report")
    source_snapshot = read_ply_snapshot(source)
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
        source_snapshot=source_snapshot,
    )
    report = clean_variants(
        source,
        evidence,
        legacy_output.parent,
        points_path=points_path,
        poses_path=poses_path,
        cam_height=cam_height,
        below_margin=below_margin,
        above_cap=above_cap,
        corridor=corridor,
        source_snapshot=source_snapshot,
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
    p.add_argument("--out", help="legacy balanced output compatibility path")
    p.add_argument("--out-dir", help="directory for all verified cleanup variants")
    p.add_argument("--evidence-artifact", help="versioned captured-evidence NPZ")
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
    if args.evidence_artifact:
        if not args.out_dir:
            p.error("--out-dir is required with --evidence-artifact")
        source_snapshot = read_ply_snapshot(args.ply)
        source_checksums = candidate_source_checksums(Path(args.ply).resolve().parent)
        artifact = load_verified_evidence_artifact(
            args.evidence_artifact,
            raw_ply_path=args.ply,
            expected_source_checksums=source_checksums,
            raw_ply_snapshot=source_snapshot,
        )
        clean_variants(
            args.ply,
            artifact.result,
            args.out_dir,
            points_path=args.points,
            poses_path=args.poses,
            cam_height=args.cam_height,
            below_margin=args.below_margin,
            above_cap=args.above_cap,
            corridor=args.corridor,
            verified_evidence=artifact,
            source_snapshot=source_snapshot,
        )
    else:
        if not args.out:
            p.error("--out is required without --evidence-artifact")
        clean(args.ply, args.points, args.out, poses_path=args.poses,
              min_opacity=args.min_opacity, max_scale=args.max_scale,
              voxel=args.voxel, dilate=args.dilate, cam_height=args.cam_height,
              below_margin=args.below_margin, above_cap=args.above_cap,
              corridor=args.corridor)


if __name__ == "__main__":
    main()
