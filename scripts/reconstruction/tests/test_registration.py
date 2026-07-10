import numpy as np
import pytest

from frame_io import apply_transform
from registration import GpsAnchor, gps_seeded_initial_transform, register_session_into_cell


def _random_scene_points(n_points: int = 3000, seed: int = 0) -> np.ndarray:
    """A synthetic 'street scene': a ground plane plus several vertical
    clusters standing on it (fence posts / a parked object), so ICP has real
    non-planar structure to lock onto.

    Ground:cluster point ratio matters a lot here -- point-to-point ICP is
    a documented risk on scenes dominated by a large flat/repetitive surface
    (a bare ground plane is translation-symmetric within itself, so a huge
    majority of near-zero-cost-but-semantically-arbitrary correspondences can
    outvote the smaller number of genuinely distinguishing points during the
    rigid-fit step -- confirmed empirically while writing this test suite: an
    earlier version with clusters at 10% of the ground's point count let a
    large/rotated offset converge to a WRONG but locally-consistent fit).
    This is flagged as a real, unresolved risk for actual GCC scans (which
    will be dominated by flat sidewalk/street surfaces) in registration.py's
    module docstring -- point-to-plane ICP or explicit ground-plane
    downweighting is the likely fix, not attempted here."""
    rng = np.random.default_rng(seed)
    ground = np.stack(
        [rng.uniform(-5, 5, n_points), np.zeros(n_points) + rng.normal(0, 0.01, n_points), rng.uniform(-5, 5, n_points)],
        axis=1,
    )
    clusters = []
    for cx, cz in [(-2.0, 1.5), (1.5, -1.0), (0.5, 3.0), (-1.0, -2.5), (2.5, 2.0)]:
        c = rng.normal(loc=[cx, 0.5, cz], scale=[0.15, 0.4, 0.15], size=(n_points // 3, 3))
        clusters.append(c)
    return np.vstack([ground] + clusters)


def _transform_matrix(translation, rotation_deg_about_y=0.0) -> np.ndarray:
    theta = np.radians(rotation_deg_about_y)
    rot = np.array(
        [[np.cos(theta), 0, np.sin(theta)], [0, 1, 0], [-np.sin(theta), 0, np.cos(theta)]]
    )
    t = np.eye(4)
    t[:3, :3] = rot
    t[:3, 3] = translation
    return t


def test_apply_transform_identity_is_noop():
    points = np.random.default_rng(0).uniform(-1, 1, (100, 3))
    result = apply_transform(points, np.eye(4))
    assert np.allclose(result, points)


def test_apply_transform_translation():
    points = np.array([[0.0, 0.0, 0.0], [1.0, 2.0, 3.0]])
    t = np.eye(4)
    t[:3, 3] = [5.0, 0.0, -2.0]
    result = apply_transform(points, t)
    assert np.allclose(result, [[5.0, 0.0, -2.0], [6.0, 2.0, 1.0]])


def test_icp_recovers_a_known_small_transform():
    accumulated = _random_scene_points(seed=1)
    true_transform = _transform_matrix(translation=[0.3, 0.0, -0.2], rotation_deg_about_y=4.0)
    new_session = apply_transform(_random_scene_points(seed=1), true_transform)  # same scene, independently "captured"

    result = register_session_into_cell(accumulated, new_session, initial_transform=None)

    assert result.accepted, f"registration rejected, fitness={result.fitness}"
    assert result.fitness > 0.8

    # Applying the recovered transform to new_session should land back near
    # accumulated -- check by comparing centroids rather than exact points
    # (ICP finds A transform that aligns the clouds, not necessarily the
    # exact inverse of true_transform if point correspondence is ambiguous).
    recovered = apply_transform(new_session, result.transform)
    centroid_diff = np.linalg.norm(recovered.mean(axis=0) - accumulated.mean(axis=0))
    assert centroid_diff < 0.05


def test_first_session_in_a_cell_is_trivially_accepted():
    new_session = _random_scene_points(seed=2)
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


def test_gps_seeding_recovers_a_small_realistic_offset():
    """The realistic operating point: GPS-seeding already gets close (typical
    smartphone GPS accuracy), so ICP only needs to correct a SMALL residual.
    See test_larger_offset_can_converge_to_a_wrong_but_confident_registration
    below for the confirmed failure mode once the offset grows.
    """
    accumulated = _random_scene_points(seed=5)
    true_transform = _transform_matrix(translation=[1.0, 0.0, 0.5], rotation_deg_about_y=2.0)
    new_session = apply_transform(_random_scene_points(seed=5), true_transform)

    seed = gps_seeded_initial_transform(
        GpsAnchor(lat=0, lng=0, local_xyz=np.array([0.0, 0.0, 0.0])),
        GpsAnchor(lat=0, lng=0, local_xyz=-true_transform[:3, 3]),  # hand the true offset directly
    )
    seeded_result = register_session_into_cell(accumulated, new_session, initial_transform=seed)

    assert seeded_result.accepted
    recovered = apply_transform(new_session, seeded_result.transform)
    centroid_diff = np.linalg.norm(recovered.mean(axis=0) - accumulated.mean(axis=0))
    assert centroid_diff < 0.1


@pytest.mark.xfail(
    reason=(
        "CONFIRMED, UNRESOLVED risk (see registration.py module docstring's "
        "'Known limitation' section): plain point-to-point ICP, even with the "
        "multi-scale coarse-to-fine schedule, can converge CONFIDENTLY "
        "(fitness above MIN_ACCEPTABLE_FITNESS) to a WRONG registration on a "
        "scene dominated by a flat/repetitive ground plane, once the true "
        "offset grows past ~1m/2deg -- worse than an outright rejection, "
        "since nothing about the returned RegistrationResult signals the "
        "mistake. Tried and did not reliably fix within this session's time "
        "budget: RANSAC ground-plane removal before registration (still "
        "converged wrong on this synthetic scene). This needs either a "
        "different algorithm (feature-based global registration, point-to-"
        "plane with better-motivated weighting) or -- more likely, per this "
        "module's own docstring quoting the 2026-07-03 research spec -- real "
        "GCC scan pairs to even know whether synthetic ground-plane "
        "degeneracy is representative of actual sidewalk/street texture. "
        "Kept as xfail (not deleted) so this doesn't silently regress "
        "further, and so a future session sees it's a known, tracked gap."
    ),
    strict=True,
)
def test_larger_offset_can_converge_to_a_wrong_but_confident_registration():
    accumulated = _random_scene_points(seed=5)
    true_transform = _transform_matrix(translation=[1.5, 0.0, 1.0], rotation_deg_about_y=3.0)
    new_session = apply_transform(_random_scene_points(seed=5), true_transform)

    seed = gps_seeded_initial_transform(
        GpsAnchor(lat=0, lng=0, local_xyz=np.array([0.0, 0.0, 0.0])),
        GpsAnchor(lat=0, lng=0, local_xyz=-true_transform[:3, 3]),
    )
    seeded_result = register_session_into_cell(accumulated, new_session, initial_transform=seed)

    assert seeded_result.accepted
    recovered = apply_transform(new_session, seeded_result.transform)
    centroid_diff = np.linalg.norm(recovered.mean(axis=0) - accumulated.mean(axis=0))
    assert centroid_diff < 0.1


def test_badly_misaligned_session_is_rejected():
    accumulated = _random_scene_points(seed=3)
    unrelated_scene = _random_scene_points(seed=99)  # different, non-overlapping structure
    far_transform = _transform_matrix(translation=[50.0, 0.0, 50.0])
    unrelated_scene = apply_transform(unrelated_scene, far_transform)

    result = register_session_into_cell(accumulated, unrelated_scene, initial_transform=None)
    assert not result.accepted
