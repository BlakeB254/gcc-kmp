import numpy as np
import open3d as o3d
import pytest

from registration import GpsAnchor, gps_seeded_initial_transform, register_session_into_cell


def _random_scene_cloud(n_points: int = 3000, seed: int = 0) -> o3d.geometry.PointCloud:
    """A synthetic 'street scene': a ground plane plus a few vertical
    clusters standing on it (fence posts / a parked object), so ICP has real
    non-planar structure to lock onto rather than a degenerate flat surface."""
    rng = np.random.default_rng(seed)
    ground = np.stack(
        [rng.uniform(-5, 5, n_points), np.zeros(n_points) + rng.normal(0, 0.01, n_points), rng.uniform(-5, 5, n_points)],
        axis=1,
    )
    clusters = []
    for cx, cz in [(-2.0, 1.5), (1.5, -1.0), (0.5, 3.0)]:
        c = rng.normal(loc=[cx, 0.5, cz], scale=[0.15, 0.4, 0.15], size=(n_points // 10, 3))
        clusters.append(c)
    points = np.vstack([ground] + clusters)
    cloud = o3d.geometry.PointCloud()
    cloud.points = o3d.utility.Vector3dVector(points)
    return cloud


def _transform_matrix(translation, rotation_deg_about_y=0.0) -> np.ndarray:
    theta = np.radians(rotation_deg_about_y)
    rot = np.array(
        [[np.cos(theta), 0, np.sin(theta)], [0, 1, 0], [-np.sin(theta), 0, np.cos(theta)]]
    )
    t = np.eye(4)
    t[:3, :3] = rot
    t[:3, 3] = translation
    return t


def test_icp_recovers_a_known_small_transform():
    accumulated = _random_scene_cloud(seed=1)
    true_transform = _transform_matrix(translation=[0.3, 0.0, -0.2], rotation_deg_about_y=4.0)
    new_session = _random_scene_cloud(seed=1)  # same scene, independently "captured"
    new_session.transform(true_transform)

    result = register_session_into_cell(accumulated, new_session, initial_transform=None)

    assert result.accepted, f"registration rejected, fitness={result.fitness}"
    assert result.fitness > 0.8

    # Applying the recovered transform to new_session should land back near
    # accumulated -- check by comparing centroids rather than exact points
    # (ICP finds A transform that aligns the clouds, not necessarily the
    # exact inverse of true_transform if point correspondence is ambiguous).
    recovered = o3d.geometry.PointCloud(new_session)
    recovered.transform(result.transform)
    centroid_diff = np.linalg.norm(
        np.asarray(recovered.points).mean(axis=0) - np.asarray(accumulated.points).mean(axis=0)
    )
    assert centroid_diff < 0.05


def test_first_session_in_a_cell_is_trivially_accepted():
    new_session = _random_scene_cloud(seed=2)
    result = register_session_into_cell(None, new_session, initial_transform=None)
    assert result.accepted
    assert np.allclose(result.transform, np.eye(4))


def test_gps_seeded_transform_recovers_known_offset():
    # Two anchors 5m east that should collapse to the same real-world point
    # once the returned transform is applied.
    accumulated_anchor = GpsAnchor(lat=41.8500, lng=-87.6500, local_xyz=np.array([0.0, 0.0, 0.0]))
    # ~5 meters east at this latitude
    meters_per_deg_lng_at_this_lat = 111_320.0 * np.cos(np.radians(41.85))
    new_anchor = GpsAnchor(
        lat=41.8500,
        lng=-87.6500 + 5.0 / meters_per_deg_lng_at_this_lat,
        local_xyz=np.array([1.0, 0.0, 1.0]),  # this session's own arbitrary local origin offset
    )

    transform = gps_seeded_initial_transform(accumulated_anchor, new_anchor, local_x_is_east=True)
    transformed_anchor = transform[:3, 3] + new_anchor.local_xyz

    # Should land within a meter of the accumulated anchor's local position
    # plus the known 5m-east real-world offset (x/east +5, z/south ~0).
    expected = accumulated_anchor.local_xyz + np.array([5.0, 0.0, 0.0])
    assert np.linalg.norm(transformed_anchor - expected) < 0.5


def test_badly_misaligned_session_is_rejected():
    accumulated = _random_scene_cloud(seed=3)
    unrelated_scene = _random_scene_cloud(seed=99)  # different, non-overlapping structure
    far_transform = _transform_matrix(translation=[50.0, 0.0, 50.0])
    unrelated_scene.transform(far_transform)

    result = register_session_into_cell(accumulated, unrelated_scene, initial_transform=None)
    assert not result.accepted
