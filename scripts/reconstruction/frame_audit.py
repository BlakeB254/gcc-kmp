"""Deterministic, CPU-only selection of trustworthy posed capture frames."""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np


MIN_SHARPNESS = 0.1


@dataclass(frozen=True)
class FrameRecord:
    index: int
    position: np.ndarray
    tracking: int
    sharpness: float
    exposure_ev: float | None
    depth_confidence_fraction: float | None


@dataclass(frozen=True)
class FrameAuditResult:
    selected_indices: list[int]
    rejections: dict[int, list[str]]
    baseline_meters: float
    normal_tracking_fraction: float
    depth_coverage_fraction: float


def _normalized(values: list[float]) -> dict[int, float]:
    """Return stable min/max quality scores keyed by input position."""
    if not values:
        return {}
    low, high = min(values), max(values)
    if high == low:
        return {index: 1.0 for index in range(len(values))}
    return {
        index: (value - low) / (high - low)
        for index, value in enumerate(values)
    }


def audit_frames(records: list[FrameRecord], budget: int) -> FrameAuditResult:
    """Reject unusable evidence and select a quality-ranked route sample.

    The input order is the capture order. Route bins use its cumulative
    camera translation, which preserves spatial coverage without relying on
    timestamps or any GPU-backed image processing.
    """
    total = len(records)
    if total == 0 or budget <= 0:
        return FrameAuditResult([], {}, 0.0, 0.0, 0.0)

    rejections: dict[int, list[str]] = {}
    for record in records:
        reasons: list[str] = []
        if record.tracking != 0:
            reasons.append("limited-tracking")
        if not np.isfinite(record.sharpness) or record.sharpness < MIN_SHARPNESS:
            reasons.append("severe-blur")
        if record.depth_confidence_fraction is not None:
            if not np.isfinite(record.depth_confidence_fraction) or record.depth_confidence_fraction <= 0:
                reasons.append("low-depth-confidence")
        if reasons:
            rejections[record.index] = reasons

    candidates = [record for record in records if record.index not in rejections]
    normal_tracking_fraction = sum(record.tracking == 0 for record in records) / total
    depth_coverage_fraction = sum(
        record.depth_confidence_fraction is not None
        and np.isfinite(record.depth_confidence_fraction)
        and record.depth_confidence_fraction > 0
        for record in records
    ) / total
    if not candidates:
        return FrameAuditResult(
            [], rejections, 0.0, normal_tracking_fraction, depth_coverage_fraction
        )

    positions = np.asarray([record.position for record in records], dtype=np.float64)
    segments = np.linalg.norm(np.diff(positions, axis=0), axis=1)
    arc_lengths = np.concatenate(([0.0], np.cumsum(segments)))
    total_length = float(arc_lengths[-1])

    sharpness_quality = _normalized([record.sharpness for record in candidates])
    known_exposures = [record.exposure_ev for record in candidates if record.exposure_ev is not None]
    exposure_center = float(np.median(known_exposures)) if known_exposures else None
    exposure_scale = (
        max(abs(exposure - exposure_center) for exposure in known_exposures)
        if exposure_center is not None
        else 0.0
    )

    quality: dict[int, float] = {}
    for candidate_index, record in enumerate(candidates):
        exposure_quality = 1.0
        if exposure_center is not None and record.exposure_ev is not None and exposure_scale > 0:
            exposure_quality = 1.0 - abs(record.exposure_ev - exposure_center) / exposure_scale
        depth_quality = (
            record.depth_confidence_fraction
            if record.depth_confidence_fraction is not None
            else 0.5
        )
        quality[record.index] = (sharpness_quality[candidate_index] + exposure_quality + depth_quality) / 3.0

    by_index = {record.index: record for record in records}
    arc_by_index = {record.index: arc_lengths[index] for index, record in enumerate(records)}
    bin_count = min(budget, len(candidates))
    bins: list[list[FrameRecord]] = [[] for _ in range(bin_count)]
    for record in candidates:
        if total_length == 0:
            bin_index = 0
        else:
            bin_index = min(int(arc_by_index[record.index] / total_length * bin_count), bin_count - 1)
        bins[bin_index].append(record)

    selected: set[int] = set()
    for route_bin in bins:
        if route_bin:
            selected.add(max(route_bin, key=lambda record: (quality[record.index], -record.index)).index)
    remaining = sorted(
        (record for record in candidates if record.index not in selected),
        key=lambda record: (-quality[record.index], record.index),
    )
    for record in remaining:
        if len(selected) >= bin_count:
            break
        selected.add(record.index)

    selected_indices = sorted(selected)
    selected_positions = np.asarray(
        [by_index[index].position for index in selected_indices], dtype=np.float64
    )
    if len(selected_positions) < 2:
        baseline_meters = 0.0
    else:
        deltas = selected_positions[:, None, :] - selected_positions[None, :, :]
        baseline_meters = float(np.max(np.linalg.norm(deltas, axis=2)))
    return FrameAuditResult(
        selected_indices,
        rejections,
        baseline_meters,
        normal_tracking_fraction,
        depth_coverage_fraction,
    )
