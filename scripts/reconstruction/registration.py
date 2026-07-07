"""Cross-session point-cloud registration — the actual open research question
flagged by docs/superpowers/specs/2026-07-03-phase3-reconstruction-fusion-research.md.
That doc narrowed the TOOL choice (Open3D for registration, this module) but
explicitly left registration accuracy on real GCC data and the activation
threshold as empirical questions this code can't answer by itself — this is
a real, runnable starting implementation, not a validated one. Test against
real multi-session captures once they exist (see repo root design doc
2026-07-07-raw-area-capture-and-fusion-pipeline-design.md) before trusting
its defaults.

Why GPS-seeded ICP instead of blind feature-based global registration
(RANSAC/FGR on FPFH features, which the research doc also considered):
ARKit sessions here use gravity+heading world alignment (ArScanner.kt), so
two sessions of the same physical area should already share axis
ORIENTATION — the only real unknown between them is a translation (plus
small residual error). GPS gives a good-enough initial translation guess
directly, which is more reliable for repetitive outdoor street geometry
(fences, sidewalks, parked cars) than feature matching, and ICP refines the
rest. Falls back to identity-seeded ICP if GPS anchors are unavailable.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np
import open3d as o3d

EARTH_RADIUS_METERS = 6_371_000.0

# ICP registration below this fitness (fraction of points with a close
# correspondence) is treated as a failed/rejected registration, per the
# design's "reject outliers that don't register cleanly" requirement — an
# empirical starting value, not validated against real data yet.
MIN_ACCEPTABLE_FITNESS = 0.3
ICP_MAX_CORRESPONDENCE_DISTANCE_METERS = 0.5


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


def register_session_into_cell(
    accumulated_cloud: o3d.geometry.PointCloud | None,
    new_session_cloud: o3d.geometry.PointCloud,
    initial_transform: np.ndarray | None,
) -> RegistrationResult:
    """Registers new_session_cloud against accumulated_cloud. When
    accumulated_cloud is None (first session for this cell), the session
    becomes the seed -- registration is trivially accepted with an identity
    transform, since there's nothing yet to align against."""
    if accumulated_cloud is None or len(accumulated_cloud.points) == 0:
        return RegistrationResult(transform=np.eye(4), fitness=1.0, inlier_rmse=0.0, accepted=True)

    seed = initial_transform if initial_transform is not None else np.eye(4)
    result = o3d.pipelines.registration.registration_icp(
        new_session_cloud,
        accumulated_cloud,
        ICP_MAX_CORRESPONDENCE_DISTANCE_METERS,
        seed,
        o3d.pipelines.registration.TransformationEstimationPointToPoint(),
    )
    return RegistrationResult(
        transform=result.transformation,
        fitness=result.fitness,
        inlier_rmse=result.inlier_rmse,
        accepted=result.fitness >= MIN_ACCEPTABLE_FITNESS,
    )
