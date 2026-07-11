"""Session -> world-splat registration solve, implementing
docs/governance/scan-splat-registration-contract.md (PR #149).

The mobile client records `manifest.geoRefEvents[]`: AR-local camera
positions paired with GPS fixes at the instant each usable fix arrived,
in the `gravityAndHeading` AR frame (+X=east, +Y=up, -Z=true north --
already gravity- and compass-aligned). This module:

  1. fits the boot-epoch clock offset  epochMs ~= a + 1000*arT
  2. segments the event stream at re-pair discontinuities (consecutive
     events whose AR travel distance disagrees with their GPS travel)
  3. per segment, solves the accuracy-weighted 2D similarity (rotation +
     translation, scale FIXED at 1 -- ARKit is metric; the free rotation
     absorbs the few-degree compass bias) from AR ground coordinates
     (arX, -arZ) to local ENU (east, north) about a shared lat0/lng0
  4. gates: <2 events, degenerate spread, or residual above the median
     GPS accuracy of the events used -> the session is LOCAL-ONLY and
     must never merge into the world layer.

Everything here is pure math over the manifest -- no R2, no DB.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

# ENU conversion per the contract: east = dLng * 111320 * cos(lat0).
METERS_PER_DEG = 111_320.0
MIN_EVENTS = 2
# A 2-event rigid fit is near-exact by construction (4 constraints, 3 DOF),
# so its residual can't attest to anything -- world eligibility needs the
# over-determined case the contract calls out (">=3 well-spread events").
MIN_EVENTS_FOR_WORLD = 3
# Below this ENU spread the rotation is unobservable (contract: "degenerate
# spread"). GPS noise at ~4m accuracy needs a baseline comfortably above it.
MIN_SPREAD_METERS = 3.0
# Re-pair discontinuity: consecutive events whose AR delta and GPS delta
# disagree by more than this (scaled by their accuracies) start a new segment.
DISCONTINUITY_SLACK_M = 5.0


@dataclass
class GeoRef:
    """One manifest geoRefEvents[] entry, parsed."""
    at_ms: float
    lat: float
    lng: float
    accuracy_m: float
    ar_xyz: np.ndarray          # (3,) AR-local camera position
    ar_t: float | None          # ARKit clock (sidecar t clock); None on old fixes

    @staticmethod
    def from_manifest(manifest: dict) -> list["GeoRef"]:
        out = []
        for e in manifest.get("geoRefEvents") or []:
            out.append(GeoRef(
                at_ms=float(e["atMs"]), lat=float(e["lat"]), lng=float(e["lng"]),
                accuracy_m=float(e.get("accuracyMeters") or 10.0),
                ar_xyz=np.array([e["arX"], e["arY"], e["arZ"]], dtype=np.float64),
                ar_t=(float(e["arTimestampSeconds"]) if e.get("arTimestampSeconds") is not None else None),
            ))
        return out


@dataclass
class Segment:
    """One contiguous solved span of the session's pose stream."""
    theta_rad: float            # AR ground frame -> ENU rotation (about +Y)
    east_m: float               # translation, meters east of (lat0, lng0)
    north_m: float
    residual_m: float           # weighted RMS over the segment's events
    event_count: int
    ar_t_start: float | None    # ARKit-clock span this segment covers
    ar_t_end: float | None
    passes_gate: bool

    def transform(self) -> np.ndarray:
        """4x4 mapping AR-local -> the world ENU frame at (lat0, lng0) with
        the pipeline's cell-frame convention: +X=east, +Y=up, -Z=north.
        A pure yaw about +Y by theta plus a ground translation -- both
        frames already share the convention, so north = -Z maps the ENU
        north translation to -Z."""
        c, s = math.cos(self.theta_rad), math.sin(self.theta_rad)
        T = np.eye(4)
        T[0, 0], T[0, 2] = c, s
        T[2, 0], T[2, 2] = -s, c
        T[0, 3] = self.east_m
        T[2, 3] = -self.north_m
        return T


@dataclass
class WorldRegistration:
    status: str                 # 'world' | 'local-only'
    lat0: float
    lng0: float
    clock_offset_ms: float | None   # a in epochMs ~= a + 1000*arT
    segments: list[Segment] = field(default_factory=list)
    reason: str | None = None   # why local-only, for provenance/debugging

    @property
    def world_eligible(self) -> bool:
        return self.status == "world"

    @property
    def primary(self) -> Segment | None:
        """The segment covering the most events among gate-passers -- the
        transform to use when placing the whole session (v1: per-segment
        pose-stream splitting is a follow-up; single-segment sessions are
        the common case)."""
        good = [s for s in self.segments if s.passes_gate]
        return max(good, key=lambda s: s.event_count) if good else None

    @property
    def residual_m(self) -> float | None:
        return self.primary.residual_m if self.primary else None

    def to_json(self) -> dict:
        return {
            "status": self.status,
            "lat0": self.lat0, "lng0": self.lng0,
            "clockOffsetMs": self.clock_offset_ms,
            "reason": self.reason,
            "segments": [{
                "thetaDeg": round(math.degrees(s.theta_rad), 3),
                "eastM": round(s.east_m, 3), "northM": round(s.north_m, 3),
                "residualM": round(s.residual_m, 3),
                "events": s.event_count,
                "arTStart": s.ar_t_start, "arTEnd": s.ar_t_end,
                "passesGate": s.passes_gate,
            } for s in self.segments],
        }


def enu_of(lat: float, lng: float, lat0: float, lng0: float) -> np.ndarray:
    return np.array([
        (lng - lng0) * METERS_PER_DEG * math.cos(math.radians(lat0)),
        (lat - lat0) * METERS_PER_DEG,
    ])


def fit_clock_offset(events: list[GeoRef], chunk_headers: list[dict] | None = None) -> float | None:
    """a in epochMs ~= a + 1000*arT, the boot-epoch bridge. Median over all
    available pairs (events with an AR timestamp, plus each schema-2 chunk
    header's epochMs/arT anchor); drift over a session is negligible."""
    pairs = [(e.at_ms, e.ar_t) for e in events if e.ar_t is not None]
    for h in chunk_headers or []:
        if h.get("epochMs") is not None and h.get("arT") is not None:
            pairs.append((float(h["epochMs"]), float(h["arT"])))
    if not pairs:
        return None
    return float(np.median([at_ms - 1000.0 * ar_t for at_ms, ar_t in pairs]))


def _solve_rigid_2d(ar_xy: np.ndarray, enu: np.ndarray, weights: np.ndarray) -> tuple[float, np.ndarray, float]:
    """Weighted least-squares rotation+translation (scale=1) mapping ar_xy
    -> enu. Returns (theta, translation(2,), weighted rms)."""
    w = weights / weights.sum()
    mu_a = (ar_xy * w[:, None]).sum(axis=0)
    mu_e = (enu * w[:, None]).sum(axis=0)
    A, E = ar_xy - mu_a, enu - mu_e
    # 2D weighted Kabsch: theta from the weighted cross/dot accumulators.
    dot = float((w * (A * E).sum(axis=1)).sum())
    cross = float((w * (A[:, 0] * E[:, 1] - A[:, 1] * E[:, 0])).sum())
    theta = math.atan2(cross, dot)
    c, s = math.cos(theta), math.sin(theta)
    R = np.array([[c, -s], [s, c]])
    t = mu_e - R @ mu_a
    resid = (R @ ar_xy.T).T + t - enu
    rms = float(np.sqrt((w * (resid ** 2).sum(axis=1)).sum()))
    return theta, t, rms


def _segment_boundaries(events: list[GeoRef], lat0: float, lng0: float) -> list[int]:
    """Indices where a new segment starts (0 is always one). A boundary is a
    consecutive pair whose AR ground travel disagrees with its GPS travel by
    more than the pair's combined accuracy allows -- the signature of an
    on-device geo-ref re-pair (contract: 'segment the pose stream at those
    points rather than averaging across them')."""
    bounds = [0]
    for i in range(1, len(events)):
        a, b = events[i - 1], events[i]
        d_ar = float(np.hypot(b.ar_xyz[0] - a.ar_xyz[0], b.ar_xyz[2] - a.ar_xyz[2]))
        d_gps = float(np.linalg.norm(enu_of(b.lat, b.lng, lat0, lng0) - enu_of(a.lat, a.lng, lat0, lng0)))
        slack = max(DISCONTINUITY_SLACK_M, 1.5 * (a.accuracy_m + b.accuracy_m))
        if abs(d_ar - d_gps) > slack:
            bounds.append(i)
    return bounds


def solve_registration(
    events: list[GeoRef],
    lat0: float,
    lng0: float,
    chunk_headers: list[dict] | None = None,
) -> WorldRegistration:
    """The full contract recipe. lat0/lng0 is the shared ENU origin -- pass
    the SAME origin for every session of a cell so their solved transforms
    are directly mutually consistent in one frame."""
    clock = fit_clock_offset(events, chunk_headers)
    if len(events) < MIN_EVENTS:
        return WorldRegistration("local-only", lat0, lng0, clock,
                                 reason=f"only {len(events)} geoRefEvents (need {MIN_EVENTS}+)")

    bounds = _segment_boundaries(events, lat0, lng0)
    segments: list[Segment] = []
    for si, start in enumerate(bounds):
        end = bounds[si + 1] if si + 1 < len(bounds) else len(events)
        seg_events = events[start:end]
        if len(seg_events) < MIN_EVENTS:
            continue
        ar_xy = np.array([[e.ar_xyz[0], -e.ar_xyz[2]] for e in seg_events])  # (x, -z) = (east-ish, north-ish)
        enu = np.array([enu_of(e.lat, e.lng, lat0, lng0) for e in seg_events])
        acc = np.array([e.accuracy_m for e in seg_events])
        spread = float(np.linalg.norm(enu.max(axis=0) - enu.min(axis=0)))
        theta, t, rms = _solve_rigid_2d(ar_xy, enu, 1.0 / np.maximum(acc, 1.0) ** 2)
        gate = float(np.median(acc))
        ar_ts = [e.ar_t for e in seg_events if e.ar_t is not None]
        segments.append(Segment(
            theta_rad=theta, east_m=float(t[0]), north_m=float(t[1]),
            residual_m=rms, event_count=len(seg_events),
            ar_t_start=(min(ar_ts) if ar_ts else None),
            ar_t_end=(max(ar_ts) if ar_ts else None),
            passes_gate=(len(seg_events) >= MIN_EVENTS_FOR_WORLD
                         and spread >= MIN_SPREAD_METERS and rms <= gate),
        ))

    reg = WorldRegistration("local-only", lat0, lng0, clock, segments)
    if any(s.passes_gate for s in segments):
        reg.status = "world"
    elif not segments:
        reg.reason = "no segment retained enough events"
    else:
        reg.reason = "no segment passed spread/residual gates"
    return reg
