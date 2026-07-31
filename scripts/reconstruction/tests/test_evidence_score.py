from __future__ import annotations

import numpy as np
import pytest

from scripts.reconstruction.evidence_score import EvidenceInputs, score_gaussians


def _poses(centers: list[tuple[float, float, float]]) -> np.ndarray:
    poses = np.repeat(np.eye(4, dtype=np.float64)[None], len(centers), axis=0)
    poses[:, :3, 3] = centers
    return poses


def _two_view_inputs(
    positions: np.ndarray,
    visibility: np.ndarray,
    colors: np.ndarray,
    *,
    lidar_points: np.ndarray,
) -> EvidenceInputs:
    count = len(positions)
    return EvidenceInputs(
        positions=positions,
        scales=np.full((count, 3), 0.05, dtype=np.float64),
        opacities=np.full(count, 0.95, dtype=np.float64),
        camera_poses=_poses([(-2.0, 0.0, 0.0), (2.0, 0.0, 0.0)]),
        intrinsics=np.repeat(np.eye(3, dtype=np.float64)[None], 2, axis=0),
        lidar_points=lidar_points,
        lidar_confidence=np.ones(len(lidar_points), dtype=np.float64),
        visibility=visibility,
        observed_colors=colors,
        reprojection_errors=np.zeros_like(visibility, dtype=np.float64),
    )


def two_view_facade_with_one_floater() -> EvidenceInputs:
    positions = np.array(
        [[-0.10, 0.0, 5.0], [0.0, 0.0, 5.0], [0.10, 0.0, 5.0], [8.0, 3.0, 2.0]],
        dtype=np.float64,
    )
    visibility = np.array([[True, True], [True, True], [True, True], [False, False]])
    colors = np.array(
        [
            [[0.3, 0.4, 0.5], [0.3, 0.4, 0.5]],
            [[0.4, 0.5, 0.6], [0.4, 0.5, 0.6]],
            [[0.5, 0.6, 0.7], [0.5, 0.6, 0.7]],
            [[np.nan, np.nan, np.nan], [np.nan, np.nan, np.nan]],
        ],
        dtype=np.float64,
    )
    return _two_view_inputs(positions, visibility, colors, lidar_points=positions[:3])


def rgb_facade_without_nearby_lidar() -> EvidenceInputs:
    positions = np.array([[-0.1, 0.0, 8.0], [0.0, 0.0, 8.0], [0.1, 0.0, 8.0]])
    visibility = np.ones((3, 2), dtype=bool)
    colors = np.repeat(
        np.array([[[0.2, 0.5, 0.7], [0.21, 0.49, 0.70]]], dtype=np.float64), 3, axis=0
    )
    return _two_view_inputs(
        positions, visibility, colors, lidar_points=np.array([[100.0, 0.0, 0.0]])
    )


def test_balanced_policy_keeps_multiview_facade_and_removes_isolated_floater():
    result = score_gaussians(two_view_facade_with_one_floater())

    assert result.balanced_mask.tolist() == [True, True, True, False]
    assert result.components["view_support"].tolist() == [1.0, 1.0, 1.0, 0.0]


def test_rgb_supported_surface_survives_beyond_lidar_range():
    result = score_gaussians(rgb_facade_without_nearby_lidar())

    assert result.balanced_mask.all()
    assert np.array_equal(result.components["lidar_proximity"], np.zeros(3))


def test_all_component_scores_are_normalized_and_policy_masks_are_nested():
    result = score_gaussians(two_view_facade_with_one_floater())

    assert set(result.components) == {
        "opacity",
        "scale",
        "anisotropy",
        "view_count",
        "view_angle_diversity",
        "reprojection_consistency",
        "lidar_proximity",
        "component_size",
        "corridor_distance",
        "view_support",
    }
    for component in result.components.values():
        assert component.shape == (4,)
        assert np.all((0.0 <= component) & (component <= 1.0))
    assert np.all(result.diagnostic_mask <= result.balanced_mask)
    assert np.all(result.balanced_mask <= result.conservative_mask)


def test_gaussian_permutation_only_permutes_deterministic_evidence():
    inputs = two_view_facade_with_one_floater()
    baseline = score_gaussians(inputs)
    order = np.array([3, 1, 0, 2])
    permuted = EvidenceInputs(
        positions=inputs.positions[order],
        scales=inputs.scales[order],
        opacities=inputs.opacities[order],
        camera_poses=inputs.camera_poses,
        intrinsics=inputs.intrinsics,
        lidar_points=inputs.lidar_points,
        lidar_confidence=inputs.lidar_confidence,
        visibility=inputs.visibility[order],
        observed_colors=inputs.observed_colors[order],
        reprojection_errors=inputs.reprojection_errors[order],
    )
    actual = score_gaussians(permuted)

    assert np.allclose(actual.total, baseline.total[order])
    for name, score in baseline.components.items():
        assert np.allclose(actual.components[name], score[order])
    assert np.array_equal(actual.conservative_mask, baseline.conservative_mask[order])
    assert np.array_equal(actual.balanced_mask, baseline.balanced_mask[order])
    assert np.array_equal(actual.diagnostic_mask, baseline.diagnostic_mask[order])


def test_returned_cleanup_candidates_are_immutable():
    result = score_gaussians(two_view_facade_with_one_floater())

    with pytest.raises(ValueError):
        result.balanced_mask[0] = False
    with pytest.raises(ValueError):
        result.components["view_support"][0] = 0.0


def test_lidar_proximity_selects_the_strongest_confidence_weighted_observation():
    inputs = _two_view_inputs(
        positions=np.array([[0.0, 0.0, 5.0]]),
        visibility=np.zeros((1, 2), dtype=bool),
        colors=np.full((1, 2, 3), np.nan),
        lidar_points=np.array([[0.0, 0.0, 5.0], [0.1, 0.0, 5.0]]),
    )
    inputs = EvidenceInputs(
        **{**inputs.__dict__, "lidar_confidence": np.array([0.0, 1.0])}
    )

    result = score_gaussians(inputs)

    assert result.components["lidar_proximity"].tolist() == [0.8]
