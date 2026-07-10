"""Cross-session point-cloud registration — the actual open research question
flagged by docs/superpowers/specs/2026-07-03-phase3-reconstruction-fusion-research.md.
That doc narrowed the TOOL choice (Open3D for registration) but explicitly
left registration accuracy on real GCC data and the activation threshold as
empirical questions this code can't answer by itself — this is a real,
runnable starting implementation, not a validated one. Test against real
multi-session captures once they exist before trusting its defaults.

ICP here is a from-scratch point-to-point implementation (SciPy cKDTree for
correspondence, NumPy SVD/Kabsch for the rigid-transform fit), NOT
o3d.pipelines.registration.registration_icp -- that function (and
o3d.geometry.KDTreeFlann underneath it) segfaults on this DGX Spark's
aarch64+Blackwell build of Open3D 0.18.0, confirmed via isolated repros with
as few as 3 points. See README's "Status" section. SciPy's cKDTree and
NumPy's SVD were independently verified to work fine on this hardware.

Why GPS-seeded ICP instead of blind feature-based global registration
(RANSAC/FGR on FPFH features, which the research doc also considered):
ARKit sessions here use gravity+heading world alignment (ArScanner.kt), so
two sessions of the same physical area should already share axis
ORIENTATION — the only real unknown between them is a translation (plus
small residual error). GPS gives a good-enough initial translation guess
directly, which is more reliable for repetitive outdoor street geometry
(fences, sidewalks, parked cars) than feature matching, and ICP refines the
rest. Falls back to identity-seeded ICP if GPS anchors are unavailable.

KNOWN LIMITATION, CONFIRMED, NOT RESOLVED (found while writing this module's
own tests -- see tests/test_registration.py's xfail'd
test_larger_offset_can_converge_to_a_wrong_but_confident_registration):
plain point-to-point ICP is prone to converging CONFIDENTLY (fitness above
MIN_ACCEPTABLE_FITNESS) to a WRONG registration on a synthetic scene
dominated by a flat/repetitive ground plane, once the true inter-session
offset exceeds roughly 1m translation / 2deg rotation -- a flat plane is
translation-symmetric within itself, so a large majority of near-zero-cost
correspondences can outvote the smaller number of genuinely distinguishing
points (fence posts, parked objects) during the rigid-fit step. The
multi-scale coarse-to-fine schedule below helps small offsets converge
reliably but does NOT fix this for larger ones. RANSAC ground-plane removal
before registration was tried and did not reliably fix it either on the
synthetic test scene.

This matters because real GCC scans will be dominated by flat sidewalk/
street surfaces -- exactly the geometry this failure mode targets. Two
things make this less alarming in practice than it sounds, but neither is
validated: (1) real captured concrete/asphalt has actual surface texture
(cracks, staining, curb edges) a perfectly flat synthetic plane doesn't,
which may provide enough disambiguating signal on its own; (2) the design's
activation-count gate (§3.1 of the 2026-07-03 research doc -- a cell isn't
canonical until N independent sessions register successfully) is the actual
safety net this pipeline should lean on, NOT a single session's fitness
score -- a future addition worth making explicit here: cross-check a new
registration's result against at least one OTHER already-accepted session's
transform (e.g. do they imply a mutually consistent relative pose?) before
trusting it, rather than accepting any single ICP call's fitness in
isolation. Not implemented yet. Re-verify all of this against real scan
pairs before trusting this module's defaults, per the research doc's own
explicit direction.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np
from scipy.spatial import cKDTree

from frame_io import apply_transform

EARTH_RADIUS_METERS = 6_371_000.0

# ICP registration below this fitness (fraction of source points with a
# close correspondence) is treated as a failed/rejected registration, per
# the design's "reject outliers that don't register cleanly" requirement —
# an empirical starting value, not validated against real data yet.
MIN_ACCEPTABLE_FITNESS = 0.3
ICP_MAX_CORRESPONDENCE_DISTANCE_METERS = 0.5
ICP_MAX_ITERATIONS = 30
ICP_CONVERGENCE_TOLERANCE = 1e-6


@dataclass
class GpsAnchor:
    lat: float
    lng: float
    local_xyz: np.ndarray  # this session's own local (x, y, z) at the anchor


@dataclass
class RegistrationResult:
    transform: np.ndarray  # 4x4, maps the new session's cloud into the accumulated cell frame
    fitness: float
    inlier_rmse: float
    accepted: bool


def _local_meters_offset(from_lat: float, from_lng: float, to_lat: float, to_lng: float) -> tuple[float, float]:
    """(east_meters, north_meters) from one lat/lng to another, flat-earth
    approximation -- fine at the ~10-50m scale of one grid cell / its
    immediate neighbors, not meant for long distances."""
    mean_lat_rad = math.radians((from_lat + to_lat) / 2.0)
    meters_per_deg_lat = 111_320.0
    meters_per_deg_lng = 111_320.0 * math.cos(mean_lat_rad)
    east = (to_lng - from_lng) * meters_per_deg_lng
    north = (to_lat - from_lat) * meters_per_deg_lat
    return east, north


def gps_seeded_initial_transform(
    accumulated_anchor: GpsAnchor,
    new_session_anchor: GpsAnchor,
    # ARKit gravity+heading alignment convention: local +X = east, local
    # +Z = south. UNVERIFIED against a real device -- flagged explicitly in
    # this module's docstring and in the 2026-07-07 design doc's handoff
    # notes. If real registrations consistently come out ~90/180 degrees off,
    # this mapping is the first thing to check.
    local_x_is_east: bool = True,
) -> np.ndarray:
    """Best-guess translation-only transform to seed ICP: assumes both
    sessions share axis orientation (gravity+heading world alignment) and
    differ only by a translation, which GPS estimates directly."""
    east, north = _local_meters_offset(
        accumulated_anchor.lat, accumulated_anchor.lng,
        new_session_anchor.lat, new_session_anchor.lng,
    )
    if local_x_is_east:
        local_offset = np.array([east, 0.0, -north])
    else:
        local_offset = np.array([north, 0.0, east])

    target_local_xyz = accumulated_anchor.local_xyz + local_offset
    translation = target_local_xyz - new_session_anchor.local_xyz

    transform = np.eye(4)
    transform[:3, 3] = translation
    return transform


def _best_fit_rigid_transform(source_points: np.ndarray, target_points: np.ndarray) -> np.ndarray:
    """Kabsch algorithm: the 4x4 rigid (rotation+translation, no scale/shear)
    transform T minimizing sum(||T @ source_i - target_i||^2), given known
    point correspondences. Standard, well-tested closed-form solution --
    the only place this pipeline needs Open3D's registration_icp was for
    correspondence search (now SciPy's cKDTree, in icp() below) plus this
    per-iteration fit, which never needed Open3D at all."""
    centroid_source = source_points.mean(axis=0)
    centroid_target = target_points.mean(axis=0)
    source_centered = source_points - centroid_source
    target_centered = target_points - centroid_target

    h = source_centered.T @ target_centered
    u, _, vt = np.linalg.svd(h)
    rotation = vt.T @ u.T
    if np.linalg.det(rotation) < 0:
        # Reflection case -- flip the smallest-singular-value axis so this
        # stays a proper rotation, not a mirror.
        vt[-1, :] *= -1
        rotation = vt.T @ u.T
    translation = centroid_target - rotation @ centroid_source

    transform = np.eye(4)
    transform[:3, :3] = rotation
    transform[:3, 3] = translation
    return transform


def icp(
    source_points: np.ndarray,
    target_points: np.ndarray,
    initial_transform: np.ndarray,
    max_correspondence_distance: float = ICP_MAX_CORRESPONDENCE_DISTANCE_METERS,
    max_iterations: int = ICP_MAX_ITERATIONS,
    tolerance: float = ICP_CONVERGENCE_TOLERANCE,
) -> RegistrationResult:
    """Point-to-point ICP: repeatedly (1) transform source by the current
    estimate, (2) find each transformed point's nearest neighbor in target
    via cKDTree, (3) keep only correspondences within
    max_correspondence_distance, (4) fit the best rigid correction for those
    pairs (Kabsch), (5) compose it onto the running transform. Converges
    when mean correspondence distance stops improving."""
    if target_points.shape[0] == 0 or source_points.shape[0] == 0:
        return RegistrationResult(transform=initial_transform, fitness=0.0, inlier_rmse=float("inf"), accepted=False)

    target_tree = cKDTree(target_points)
    transform = initial_transform.copy()
    prev_mean_error = None
    fitness = 0.0
    inlier_rmse = float("inf")

    for _ in range(max_iterations):
        transformed_source = apply_transform(source_points, transform)
        distances, indices = target_tree.query(transformed_source, k=1)
        mask = distances < max_correspondence_distance
        n_inliers = int(mask.sum())
        if n_inliers < 3:
            # Not enough correspondences to fit a rigid transform at all --
            # stop where we are rather than let Kabsch run on <3 points.
            break

        fitness = n_inliers / source_points.shape[0]
        inlier_rmse = float(np.sqrt(np.mean(distances[mask] ** 2)))

        matched_source = transformed_source[mask]
        matched_target = target_points[indices[mask]]
        incremental = _best_fit_rigid_transform(matched_source, matched_target)
        transform = incremental @ transform

        mean_error = float(np.mean(distances[mask]))
        if prev_mean_error is not None and abs(prev_mean_error - mean_error) < tolerance:
            break
        prev_mean_error = mean_error

    return RegistrationResult(
        transform=transform,
        fitness=fitness,
        inlier_rmse=inlier_rmse,
        accepted=fitness >= MIN_ACCEPTABLE_FITNESS,
    )


# Coarse-to-fine correspondence-distance schedule. A single fixed-threshold
# ICP pass measurably fails to converge on larger inter-session offsets (3m
# translation + ~8deg rotation, empirically, while building this module's
# tests) even from a decent seed -- a tight threshold's correspondences are
# all wrong until the estimate is already close, so it can get stuck. This is
# the standard fix (used in KinectFusion-style pipelines and elsewhere): the
# first, loose pass gets roughly aligned even from a mediocre seed, and each
# following pass refines with a tighter threshold once the estimate is
# already in the right neighborhood.
MULTI_SCALE_CORRESPONDENCE_DISTANCES_METERS = [2.0, 1.0, 0.5]


def multi_scale_icp(
    source_points: np.ndarray,
    target_points: np.ndarray,
    initial_transform: np.ndarray,
    correspondence_distances: list[float] = MULTI_SCALE_CORRESPONDENCE_DISTANCES_METERS,
) -> RegistrationResult:
    result = RegistrationResult(transform=initial_transform, fitness=0.0, inlier_rmse=float("inf"), accepted=False)
    for distance in correspondence_distances:
        result = icp(source_points, target_points, result.transform, max_correspondence_distance=distance)
    return result


def register_session_into_cell(
    accumulated_points: np.ndarray | None,
    new_session_points: np.ndarray,
    initial_transform: np.ndarray | None,
) -> RegistrationResult:
    """Registers new_session_points against accumulated_points (both plain
    Nx3 NumPy arrays -- see frame_io.PointCloudArrays.points). When
    accumulated_points is None or empty (first session for this cell), the
    session becomes the seed -- registration is trivially accepted with an
    identity transform, since there's nothing yet to align against."""
    if accumulated_points is None or accumulated_points.shape[0] == 0:
        return RegistrationResult(transform=np.eye(4), fitness=1.0, inlier_rmse=0.0, accepted=True)

    seed = initial_transform if initial_transform is not None else np.eye(4)
    return multi_scale_icp(new_session_points, accumulated_points, seed)
