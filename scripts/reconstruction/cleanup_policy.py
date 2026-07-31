"""Pure CPU evaluation of verified Gaussian cleanup policies."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

import numpy as np

from evidence_score import EvidenceResult


POLICY_NAMES = ("conservative", "balanced", "diagnostic")
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
HISTOGRAM_EDGES = np.linspace(0.0, 1.0, 6)
SPATIAL_THRESHOLD_NAMES = ("camHeight", "belowMargin", "aboveCap", "corridor")
FLOATER_EVIDENCE_SCORE_THRESHOLD = 0.75
FLOATER_LIDAR_DIRECT_SUPPORT_THRESHOLD = 0.8
FLOATER_VERIFIED_VIEW_SUPPORT_THRESHOLD = 1.0
RETAINED_COVERAGE_VOXEL_SIZE_METERS = 0.5


@dataclass(frozen=True)
class CleanupEvaluation:
    total: np.ndarray
    components: dict[str, np.ndarray]
    masks: dict[str, np.ndarray]
    spatial_reasons: dict[str, np.ndarray]
    counts: dict[str, int]
    histograms: dict[str, dict[str, list[Any]]]
    removed_reason_counts: dict[str, dict[str, int]]
    quality_metrics: dict[str, Any]


def build_policy_thresholds(
    base_thresholds: Mapping[str, Mapping[str, Any]],
    *,
    cam_height: float,
    below_margin: float,
    above_cap: float,
    corridor: float,
) -> dict[str, dict[str, Any]]:
    if set(base_thresholds) != set(POLICY_NAMES):
        raise ValueError("policy thresholds must describe all three cleanup policies")
    return {
        policy: {
            **base_thresholds[policy],
            "camHeight": cam_height,
            "belowMargin": below_margin,
            "aboveCap": above_cap,
            "corridor": corridor,
        }
        for policy in POLICY_NAMES
    }


def validate_policy_thresholds(
    thresholds: Any, *, require_verified_base: bool
) -> dict[str, dict[str, Any]]:
    if not isinstance(thresholds, dict) or set(thresholds) != set(POLICY_NAMES):
        raise ValueError("cleanup report policy thresholds are invalid")
    normalized: dict[str, dict[str, Any]] = {}
    for policy in POLICY_NAMES:
        values = thresholds[policy]
        if not isinstance(values, dict) or not set(SPATIAL_THRESHOLD_NAMES) <= set(values):
            raise ValueError("cleanup report policy thresholds are incomplete")
        numeric: dict[str, float] = {}
        for name in SPATIAL_THRESHOLD_NAMES:
            value = values[name]
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ValueError("cleanup report policy threshold types are invalid")
            value = float(value)
            if not np.isfinite(value):
                raise ValueError("cleanup report policy threshold values are invalid")
            numeric[name] = value
        if (
            numeric["camHeight"] <= 0.0
            or numeric["belowMargin"] < 0.0
            or numeric["aboveCap"] < 0.0
            or numeric["corridor"] <= 0.0
        ):
            raise ValueError("cleanup report policy threshold values are invalid")
        if require_verified_base:
            expected = POLICY_THRESHOLDS[policy]
            if set(values) != {*expected, *SPATIAL_THRESHOLD_NAMES}:
                raise ValueError("cleanup report policy thresholds are invalid")
            if values.get("directSupport") != expected["directSupport"]:
                raise ValueError("cleanup report policy thresholds are invalid")
            total_score = values.get("totalScore")
            if (
                isinstance(total_score, bool)
                or not isinstance(total_score, (int, float))
                or not np.isfinite(float(total_score))
                or float(total_score) != expected["totalScore"]
            ):
                raise ValueError("cleanup report policy thresholds are invalid")
        normalized[policy] = {**values, **numeric}
    spatial = {
        name: normalized[POLICY_NAMES[0]][name] for name in SPATIAL_THRESHOLD_NAMES
    }
    if any(
        normalized[policy][name] != value
        for policy in POLICY_NAMES[1:]
        for name, value in spatial.items()
    ):
        raise ValueError("cleanup report spatial thresholds differ by policy")
    return normalized


def _validated_evidence(
    evidence: EvidenceResult, count: int
) -> tuple[np.ndarray, dict[str, np.ndarray], dict[str, np.ndarray]]:
    total = np.asarray(evidence.total, dtype=np.float64)
    if total.shape != (count,) or not np.isfinite(total).all():
        raise ValueError("evidence total must be finite and match the PLY vertex count")
    if np.any((total < 0.0) | (total > 1.0)):
        raise ValueError("evidence total must be normalized to [0, 1]")
    components: dict[str, np.ndarray] = {}
    for name, values in evidence.components.items():
        if name in {"total", *POLICY_NAMES}:
            raise ValueError(f"evidence component name {name!r} is reserved")
        component = np.asarray(values, dtype=np.float64)
        if component.shape != (count,) or not np.isfinite(component).all():
            raise ValueError(f"evidence component {name!r} must match the PLY vertex count")
        if np.any((component < 0.0) | (component > 1.0)):
            raise ValueError(f"evidence component {name!r} must be normalized to [0, 1]")
        components[name] = component
    masks = {
        policy: np.asarray(getattr(evidence, f"{policy}_mask"), dtype=bool)
        for policy in POLICY_NAMES
    }
    if any(mask.shape != (count,) for mask in masks.values()):
        raise ValueError("evidence masks must match the PLY vertex count")
    if np.any(masks["balanced"] & ~masks["conservative"]):
        raise ValueError("balanced evidence mask must be nested inside conservative")
    if np.any(masks["diagnostic"] & ~masks["balanced"]):
        raise ValueError("diagnostic evidence mask must be nested inside balanced")
    return total, components, masks


def _histograms(components: dict[str, np.ndarray]) -> dict[str, dict[str, list[Any]]]:
    result = {}
    for name, values in sorted(components.items()):
        counts, edges = np.histogram(values, bins=HISTOGRAM_EDGES)
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
    policy_thresholds: dict[str, dict[str, Any]],
    spatial_reasons: dict[str, np.ndarray],
) -> dict[str, int]:
    removed = ~mask
    reasons = {"policy-mask-rejected": int(removed.sum())}
    total_threshold = policy_thresholds[policy].get("totalScore")
    if isinstance(total_threshold, (int, float)) and not isinstance(total_threshold, bool):
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
    for name, values in spatial_reasons.items():
        reasons[name] = int(np.sum(removed & values))
    return reasons


def _quality_metrics(
    vertices: np.ndarray,
    total: np.ndarray,
    components: dict[str, np.ndarray],
    masks: dict[str, np.ndarray],
) -> dict[str, Any]:
    """Measure weak retained evidence and evidence-supported voxel coverage.

    A balanced Gaussian is weak when it does not meet the stricter diagnostic
    evidence score/direct-support thresholds. Coverage uses occupied 0.5 m
    voxels from the evidence-supported conservative policy as its reference;
    it is not a Gaussian-count or selected-frame ratio.
    """
    balanced = masks["balanced"]
    conservative = masks["conservative"]
    balanced_count = int(balanced.sum())
    view_support = components.get("view_support")
    lidar_proximity = components.get("lidar_proximity")
    if balanced_count == 0 or view_support is None or lidar_proximity is None:
        floater_ratio: float | None = None
        weak_count = 0
    else:
        strong = (total >= FLOATER_EVIDENCE_SCORE_THRESHOLD) & (
            (view_support >= FLOATER_VERIFIED_VIEW_SUPPORT_THRESHOLD)
            | (lidar_proximity >= FLOATER_LIDAR_DIRECT_SUPPORT_THRESHOLD)
        )
        weak_count = int(np.sum(balanced & ~strong))
        floater_ratio = weak_count / balanced_count

    xyz = np.stack([vertices[name] for name in ("x", "y", "z")], axis=1)
    if not np.isfinite(xyz).all():
        raise ValueError("cleanup vertices contain non-finite positions")
    voxel_indices = np.floor(
        xyz / RETAINED_COVERAGE_VOXEL_SIZE_METERS
    ).astype(np.int64)

    def occupied(mask: np.ndarray) -> set[tuple[int, int, int]]:
        return {
            tuple(int(value) for value in voxel)
            for voxel in voxel_indices[mask]
        }

    reference_voxels = occupied(conservative)
    retained_voxels = occupied(balanced) & reference_voxels
    retained_coverage = (
        len(retained_voxels) / len(reference_voxels)
        if reference_voxels
        else None
    )
    return {
        "floaterRatio": floater_ratio,
        "retainedCoverage": retained_coverage,
        "definitions": {
            "floaterRatio": {
                "evidenceScoreThreshold": FLOATER_EVIDENCE_SCORE_THRESHOLD,
                "lidarDirectSupportThreshold": FLOATER_LIDAR_DIRECT_SUPPORT_THRESHOLD,
                "verifiedViewSupportThreshold": FLOATER_VERIFIED_VIEW_SUPPORT_THRESHOLD,
                "weakRetainedGaussians": weak_count,
                "balancedGaussians": balanced_count,
            },
            "retainedCoverage": {
                "referencePolicy": "conservative",
                "voxelSizeMeters": RETAINED_COVERAGE_VOXEL_SIZE_METERS,
                "retainedOccupiedVoxels": len(retained_voxels),
                "referenceOccupiedVoxels": len(reference_voxels),
            },
        },
    }


def evaluate_cleanup(
    vertices: np.ndarray,
    evidence: EvidenceResult,
    policy_thresholds: dict[str, dict[str, Any]],
    *,
    poses: np.ndarray | None,
) -> CleanupEvaluation:
    count = len(vertices)
    total, components, masks = _validated_evidence(evidence, count)
    spatial_reasons = {
        "below-ground": np.zeros(count, dtype=bool),
        "above-cap": np.zeros(count, dtype=bool),
        "outside-corridor": np.zeros(count, dtype=bool),
    }
    if poses is not None:
        poses = np.asarray(poses)
        if poses.ndim != 3 or poses.shape[1:] != (4, 4) or len(poses) == 0:
            raise ValueError("poses must contain at least one camera-to-world 4x4 matrix")
        if not np.isfinite(poses).all():
            raise ValueError("poses contain non-finite values")
        settings = policy_thresholds["balanced"]
        xyz = np.stack([vertices[name] for name in ("x", "y", "z")], axis=1)
        cameras = poses[:, :3, 3]
        ground = float(np.median(cameras[:, 1]) - settings["camHeight"])
        spatial_reasons["below-ground"] = xyz[:, 1] < (
            ground - settings["belowMargin"]
        )
        if settings["aboveCap"] > 0:
            spatial_reasons["above-cap"] = xyz[:, 1] > (
                ground + settings["aboveCap"]
            )
        horizontal_distance_squared = np.full(count, np.inf)
        for camera in cameras[::4]:
            distance = (xyz[:, 0] - camera[0]) ** 2 + (xyz[:, 2] - camera[2]) ** 2
            np.minimum(horizontal_distance_squared, distance, out=horizontal_distance_squared)
        spatial_reasons["outside-corridor"] = (
            horizontal_distance_squared > settings["corridor"] ** 2
        )
        spatial_rejected = np.logical_or.reduce(tuple(spatial_reasons.values()))
        masks = {policy: mask & ~spatial_rejected for policy, mask in masks.items()}
    counts = {"source": count, **{policy: int(mask.sum()) for policy, mask in masks.items()}}
    return CleanupEvaluation(
        total=total,
        components=components,
        masks=masks,
        spatial_reasons=spatial_reasons,
        counts=counts,
        histograms=_histograms(components),
        removed_reason_counts={
            policy: _removed_reasons(
                policy,
                masks[policy],
                total,
                components,
                policy_thresholds,
                spatial_reasons,
            )
            for policy in POLICY_NAMES
        },
        quality_metrics=_quality_metrics(vertices, total, components, masks),
    )
