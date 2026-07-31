from __future__ import annotations

from dataclasses import replace

import numpy as np
import pytest

from scripts.reconstruction.evidence_score import EvidenceInputs, score_gaussians


def _poses(centers: list[tuple[float, float, float]]) -> np.ndarray:
    poses = np.repeat(np.eye(4, dtype=np.float64)[None], len(centers), axis=0)
    poses[:, :3, 3] = centers
    return poses


def _captured_pixels(
    positions: np.ndarray,
    poses: np.ndarray,
    intrinsics: np.ndarray,
    visibility: np.ndarray,
) -> np.ndarray:
    """Synthetic captured pixels, not a production visibility generator."""
    pixels = np.full((*visibility.shape, 2), np.nan, dtype=np.float64)
    for view, pose in enumerate(poses):
        camera_points = (positions - pose[:3, 3]) @ pose[:3, :3]
        homogeneous = camera_points @ intrinsics[view].T
        projected = homogeneous[:, :2] / homogeneous[:, 2:3]
        pixels[visibility[:, view], view] = projected[visibility[:, view]]
    return pixels


def _two_view_inputs(
    positions: np.ndarray,
    visibility: np.ndarray,
    colors: np.ndarray,
    *,
    lidar_points: np.ndarray,
) -> EvidenceInputs:
    count = len(positions)
    camera_poses = _poses([(-2.0, 0.0, 0.0), (2.0, 0.0, 0.0)])
    intrinsics = np.repeat(np.eye(3, dtype=np.float64)[None], 2, axis=0)
    return EvidenceInputs(
        positions=positions,
        scales=np.full((count, 3), 0.05, dtype=np.float64),
        opacities=np.full(count, 0.95, dtype=np.float64),
        camera_poses=camera_poses,
        intrinsics=intrinsics,
        lidar_points=lidar_points,
        lidar_confidence=np.ones(len(lidar_points), dtype=np.float64),
        visibility=visibility,
        observed_colors=colors,
        observed_pixels=_captured_pixels(positions, camera_poses, intrinsics, visibility),
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
        observed_pixels=inputs.observed_pixels[order],
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
    inputs = replace(inputs, lidar_confidence=np.array([0.0, 1.0]))

    result = score_gaussians(inputs)

    assert result.components["lidar_proximity"].tolist() == [0.8]


def test_missing_lidar_confidence_cannot_keep_an_unverified_gaussian():
    inputs = _two_view_inputs(
        positions=np.array([[0.0, 0.0, 5.0]]),
        visibility=np.zeros((1, 2), dtype=bool),
        colors=np.full((1, 2, 3), np.nan),
        lidar_points=np.array([[0.0, 0.0, 5.0]]),
    )

    result = score_gaussians(replace(inputs, lidar_confidence=None))

    assert result.components["lidar_proximity"].tolist() == [0.0]
    assert not result.conservative_mask.any()


def test_missing_reprojection_pixels_cannot_enable_rgb_view_support():
    inputs = rgb_facade_without_nearby_lidar()

    result = score_gaussians(replace(inputs, observed_pixels=None))

    assert np.array_equal(result.components["reprojection_consistency"], np.zeros(3))
    assert np.array_equal(result.components["view_support"], np.zeros(3))
    assert not result.conservative_mask.any()


def test_calibration_derived_pixel_error_rejects_caller_asserted_rgb_support():
    inputs = rgb_facade_without_nearby_lidar()
    incorrect_pixels = inputs.observed_pixels.copy()
    incorrect_pixels[inputs.visibility] += 30.0

    result = score_gaussians(replace(inputs, observed_pixels=incorrect_pixels))

    assert np.array_equal(result.components["reprojection_consistency"], np.zeros(3))
    assert np.array_equal(result.components["view_support"], np.zeros(3))
    assert not result.conservative_mask.any()


def _random_valid_inputs(seed: int) -> EvidenceInputs:
    rng = np.random.default_rng(seed)
    gaussian_count, view_count, lidar_count = 1 + seed % 9, 2 + seed % 3, 1 + seed % 7
    positions = np.column_stack(
        (rng.uniform(-2.0, 2.0, gaussian_count), rng.uniform(-1.0, 1.0, gaussian_count), rng.uniform(3.0, 9.0, gaussian_count))
    )
    poses = _poses([(float(index) - view_count / 2.0, 0.0, 0.0) for index in range(view_count)])
    intrinsics = np.repeat(
        np.array([[[300.0, 0.0, 320.0], [0.0, 300.0, 240.0], [0.0, 0.0, 1.0]]]),
        view_count,
        axis=0,
    )
    visibility = rng.random((gaussian_count, view_count)) > 0.35
    colors = np.full((gaussian_count, view_count, 3), np.nan)
    base_colors = rng.random((gaussian_count, 3))
    colors[visibility] = np.broadcast_to(base_colors[:, None, :], colors.shape)[visibility]
    return EvidenceInputs(
        positions=positions,
        scales=rng.uniform(0.01, 0.2, (gaussian_count, 3)),
        opacities=rng.uniform(0.0, 1.0, gaussian_count),
        camera_poses=poses,
        intrinsics=intrinsics,
        lidar_points=rng.uniform([-3.0, -2.0, 2.0], [3.0, 2.0, 10.0], (lidar_count, 3)),
        lidar_confidence=rng.random(lidar_count),
        visibility=visibility,
        observed_colors=colors,
        observed_pixels=_captured_pixels(positions, poses, intrinsics, visibility),
    )


def test_randomized_valid_inputs_have_nested_and_permutation_equivariant_masks():
    for seed in range(20):
        inputs = _random_valid_inputs(seed)
        baseline = score_gaussians(inputs)
        assert np.all(baseline.diagnostic_mask <= baseline.balanced_mask)
        assert np.all(baseline.balanced_mask <= baseline.conservative_mask)

        order = np.random.default_rng(seed + 100).permutation(len(inputs.positions))
        actual = score_gaussians(
            replace(
                inputs,
                positions=inputs.positions[order],
                scales=inputs.scales[order],
                opacities=inputs.opacities[order],
                visibility=inputs.visibility[order],
                observed_colors=inputs.observed_colors[order],
                observed_pixels=inputs.observed_pixels[order],
            )
        )
        assert np.allclose(actual.total, baseline.total[order])
        for name, component in baseline.components.items():
            assert np.allclose(actual.components[name], component[order])
        assert np.array_equal(actual.diagnostic_mask, baseline.diagnostic_mask[order])
        assert np.array_equal(actual.balanced_mask, baseline.balanced_mask[order])
        assert np.array_equal(actual.conservative_mask, baseline.conservative_mask[order])
