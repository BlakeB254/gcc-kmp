from __future__ import annotations

import numpy as np

import gsplat_runner


def _poses(points: list[tuple[float, float, float]]) -> np.ndarray:
    poses = np.repeat(np.eye(4, dtype=np.float64)[None], len(points), axis=0)
    poses[:, :3, 3] = points
    return poses


def test_rejects_scan_that_cannot_support_a_novel_view():
    """Catches publication of a fuzzy splat trained from a near-static scan."""
    quality_gate = getattr(gsplat_runner, "splat_quality_gate", None)
    assert callable(quality_gate), "splat publication quality gate is missing"

    poses = _poses([(0.0, 1.6, 0.0), (0.4, 1.6, 0.2), (0.9, 1.6, 0.4)])

    assert quality_gate(poses) == "insufficient-parallax"


def test_rejects_pose_track_that_is_implausibly_large_for_one_cell():
    """Catches cross-session registration jumps entering one cell model."""
    quality_gate = getattr(gsplat_runner, "splat_quality_gate", None)
    assert callable(quality_gate), "splat publication quality gate is missing"

    poses = _poses([(0.0, 1.6, 0.0), (5.0, 1.6, 4.0), (80.0, 1.6, 65.0)])

    assert quality_gate(poses) == "implausible-pose-span"


def test_accepts_a_walk_with_useful_parallax_inside_a_cell():
    """Catches an over-strict gate that would suppress a healthy street scan."""
    quality_gate = getattr(gsplat_runner, "splat_quality_gate", None)
    assert callable(quality_gate), "splat publication quality gate is missing"

    poses = _poses([(0.0, 1.6, 0.0), (1.5, 1.6, -1.0), (3.0, 1.7, -2.0)])

    assert quality_gate(poses) is None


def test_frame_selector_drops_blurry_candidates_before_training():
    """Catches a low-detail frame surviving while sharper coverage is available."""
    selector = getattr(gsplat_runner, "select_training_frame_indices", None)
    assert callable(selector), "training-frame quality selector is missing"

    sharp = np.indices((32, 32)).sum(axis=0) % 2
    images = [
        np.repeat((sharp * 255).astype(np.uint8)[..., None], 3, axis=2)
        for _ in range(10)
    ]
    images[5] = np.full((32, 32, 3), 127, dtype=np.uint8)

    selected = selector(images, budget=8)

    assert len(selected) == 8
    assert 5 not in selected
    assert selected == sorted(selected)


def test_frame_selector_keeps_short_capture_intact():
    """Catches quality filtering needlessly deleting frames below the budget."""
    selector = getattr(gsplat_runner, "select_training_frame_indices", None)
    assert callable(selector), "training-frame quality selector is missing"
    images = [np.full((16, 16, 3), value, dtype=np.uint8) for value in (20, 40, 60)]

    assert selector(images, budget=8) == [0, 1, 2]
