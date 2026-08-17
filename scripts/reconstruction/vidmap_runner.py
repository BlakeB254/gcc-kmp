"""VidMap pose backbone for outdoor walk-capture bundles (P0 / P1 handoff).

Walk video or frames + optional GPS → COLMAP model + GPS-anchored Sim3 +
WorldMirror ``prior_camera.json``. SuperSplat / WorldMirror remain the
recon/editor; this module does not produce meshes.

The ETH CVG CLI (~/opensource/vidmap, Apache-2.0, arXiv 2607.27194) is
invoked only as a subprocess behind ``VIDMAP_BIN`` / ``VIDMAP_REPO``.
Default ``--dry-run`` writes the output schema with no weights and no GPU.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from dataclasses import dataclass, field
from pathlib import Path
import shlex
import struct
import subprocess
import sys

import numpy as np

from gaussian_ply import umeyama_sim3
from gpu_admission import GpuReservation
from world_mirror_client import (
    PRIOR_CAM_SCHEMA,
    build_prior_cam_payload,
    validate_prior_cam_payload,
)

# Privacy-quarantined 2026-08-10 (PRD): indoor residential, not a civic walk.
QUARANTINED_BUNDLE_MARKERS = ("rayban_wwgo3126_douglass_park",)

OUTPUT_REL = Path("rec") / "vidmap"
COLMAP_REL = OUTPUT_REL / "colmap"
QUALITY_NAME = "quality.json"
POSES_GPS_NAME = "poses_gps.json"
PRIOR_CAM_NAME = "prior_camera.json"
SCHEMA_VERSION = 1

VIDEO_SUFFIXES = {".mp4", ".mov", ".m4v"}
FRAME_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}
GPS_FILENAMES = (
    "gps.jsonl",
    "track.jsonl",
    "gps_track.jsonl",
    "gps.json",
    "track.json",
    "path.json",
    "path.jsonl",
)

# WGS84 ellipsoid (EPSG:4326 geodetic → EPSG:4978 ECEF).
_WGS84_A = 6378137.0
_WGS84_F = 1.0 / 298.257223563
_WGS84_E2 = _WGS84_F * (2.0 - _WGS84_F)

_COLMAP_CAMERA_MODELS = {
    0: ("SIMPLE_PINHOLE", 3),
    1: ("PINHOLE", 4),
    2: ("SIMPLE_RADIAL", 4),
    3: ("RADIAL", 5),
    4: ("OPENCV", 8),
    5: ("OPENCV_FISHEYE", 8),
    6: ("FULL_OPENCV", 12),
    7: ("FOV", 5),
    8: ("SIMPLE_RADIAL_FISHEYE", 4),
    9: ("RADIAL_FISHEYE", 5),
    10: ("THIN_PRISM_FISHEYE", 12),
}

MIN_KEYFRAMES_GO = 8
LOOP_CLOSE_FRACTION = 0.15


class QuarantinedBundleError(ValueError):
    """Raised when the bundle name matches a privacy-quarantined capture."""


class VidmapError(RuntimeError):
    pass


@dataclass
class GpsSample:
    lat: float
    lon: float
    alt_m: float = 0.0
    t: float | None = None
    name: str | None = None


@dataclass
class ColmapCamera:
    camera_id: int
    model: str
    width: int
    height: int
    params: list[float]


@dataclass
class ColmapImage:
    image_id: int
    qvec: np.ndarray  # qw, qx, qy, qz  (world-to-camera)
    tvec: np.ndarray  # tx, ty, tz
    camera_id: int
    name: str
    points2d: list = field(default_factory=list)


@dataclass
class ColmapPoint:
    point_id: int
    xyz: np.ndarray
    rgb: tuple[int, int, int] = (0, 0, 0)
    error: float = 0.0
    track: list = field(default_factory=list)


@dataclass
class ColmapModel:
    cameras: list[ColmapCamera] = field(default_factory=list)
    images: list[ColmapImage] = field(default_factory=list)
    points: list[ColmapPoint] = field(default_factory=list)

    @property
    def empty(self) -> bool:
        return not self.images


@dataclass
class BundleInputs:
    bundle_dir: Path
    video: Path | None = None
    frames_dir: Path | None = None
    gps_path: Path | None = None
    gps: list[GpsSample] = field(default_factory=list)

    @property
    def input_data(self) -> Path | None:
        if self.frames_dir is not None:
            return self.frames_dir
        return self.video


@dataclass
class VidmapResult:
    bundle_dir: Path
    output_dir: Path
    colmap_dir: Path
    quality: dict
    poses_gps: dict
    prior_camera: dict
    status: str


def assert_not_quarantined(bundle_dir: str | os.PathLike[str]) -> None:
    """Refuse indoor-residential Ray-Ban captures retired 2026-08-10."""
    path = Path(bundle_dir).expanduser()
    try:
        resolved = path.resolve()
    except OSError:
        resolved = path
    haystack = f"{resolved.name} {resolved}".lower()
    for marker in QUARANTINED_BUNDLE_MARKERS:
        if marker in haystack:
            raise QuarantinedBundleError(
                f"refusing quarantined bundle {path.name!r} "
                f"(marker={marker}; indoor residential, retired 2026-08-10)"
            )


def vidmap_command() -> list[str] | None:
    """Return the VidMap CLI argv, or None when it is not configured.

    The source checkout at ~/opensource/vidmap is not treated as installed:
    operators must set VIDMAP_BIN or VIDMAP_REPO so a dry-run on this box
    stays ``status=not_run`` / ``reason=vidmap_not_installed`` and never
    touches the ~9GB checkpoints.
    """
    bin_env = os.environ.get("VIDMAP_BIN", "").strip()
    if bin_env:
        return shlex.split(bin_env)
    repo_env = os.environ.get("VIDMAP_REPO", "").strip()
    if not repo_env:
        return None
    repo = Path(repo_env).expanduser()
    if (repo / "vidmap" / "run.py").is_file():
        return [sys.executable, "-m", "vidmap.run"]
    return None


def output_dir_for(bundle_dir: str | os.PathLike[str]) -> Path:
    return Path(bundle_dir).expanduser() / OUTPUT_REL


def colmap_dir_for(bundle_dir: str | os.PathLike[str]) -> Path:
    return Path(bundle_dir).expanduser() / COLMAP_REL


# ---------------------------------------------------------------------------
# Bundle discovery
# ---------------------------------------------------------------------------

def _is_frame_dir(path: Path) -> bool:
    if not path.is_dir():
        return False
    return any(p.suffix.lower() in FRAME_SUFFIXES for p in path.iterdir() if p.is_file())


def _first_video(root: Path) -> Path | None:
    candidates: list[Path] = []
    for folder in (root, root / "video", root / "videos"):
        if not folder.is_dir():
            continue
        for path in sorted(folder.iterdir()):
            if path.is_file() and path.suffix.lower() in VIDEO_SUFFIXES:
                candidates.append(path)
    return candidates[0] if candidates else None


def _first_frames_dir(root: Path) -> Path | None:
    for name in ("frames", "images", "keyframes"):
        candidate = root / name
        if _is_frame_dir(candidate):
            return candidate
    if _is_frame_dir(root):
        return root
    return None


def _looks_like_gps_record(obj: object) -> bool:
    if not isinstance(obj, dict):
        return False
    return any(k in obj for k in ("lat", "latitude", "lng", "lon", "longitude"))


def _parse_gps_record(obj: dict) -> GpsSample | None:
    lat = obj.get("lat", obj.get("latitude"))
    lon = obj.get("lon", obj.get("lng", obj.get("longitude")))
    if lat is None or lon is None:
        return None
    alt = obj.get("alt", obj.get("alt_m", obj.get("altitude", obj.get("altitude_m", 0.0))))
    t = obj.get("t", obj.get("time", obj.get("timestamp", obj.get("atMs", obj.get("at_ms")))))
    if t is not None:
        t = float(t)
        # Manifest wall-clock ms would dwarf sidecar seconds; keep as-is for
        # relative matching (both sides of a pair share a clock).
    name = obj.get("name", obj.get("image", obj.get("frame", obj.get("id"))))
    return GpsSample(
        lat=float(lat),
        lon=float(lon),
        alt_m=float(alt or 0.0),
        t=t,
        name=(str(name) if name is not None else None),
    )


def load_gps_track(path: str | os.PathLike[str]) -> list[GpsSample]:
    raw = Path(path).read_text(encoding="utf-8")
    stripped = raw.lstrip()
    records: list[dict] = []
    if stripped.startswith("{") or stripped.startswith("["):
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            payload = None
        if isinstance(payload, list):
            records = [r for r in payload if isinstance(r, dict)]
        elif isinstance(payload, dict):
            for key in ("track", "path", "gps", "samples", "geoRefEvents", "points"):
                value = payload.get(key)
                if isinstance(value, list):
                    records = [r for r in value if isinstance(r, dict)]
                    break
            if not records and _looks_like_gps_record(payload):
                records = [payload]
    if not records:
        for line in raw.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(obj, dict) and _looks_like_gps_record(obj):
                records.append(obj)
    samples = []
    for record in records:
        sample = _parse_gps_record(record)
        if sample is not None:
            samples.append(sample)
    return samples


def _discover_gps(root: Path) -> tuple[Path | None, list[GpsSample]]:
    for name in GPS_FILENAMES:
        candidate = root / name
        if candidate.is_file():
            samples = load_gps_track(candidate)
            if samples:
                return candidate, samples
    manifest = root / "manifest.json"
    if manifest.is_file():
        try:
            payload = json.loads(manifest.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            payload = None
        if isinstance(payload, dict):
            for key in ("geoRefEvents", "path", "gps", "track"):
                value = payload.get(key)
                if isinstance(value, list) and value and _looks_like_gps_record(value[0]):
                    samples = []
                    for record in value:
                        if isinstance(record, dict):
                            sample = _parse_gps_record(record)
                            if sample is not None:
                                samples.append(sample)
                    if samples:
                        return manifest, samples
    for candidate in sorted(root.glob("*.jsonl")):
        if candidate.name in GPS_FILENAMES:
            continue
        samples = load_gps_track(candidate)
        if samples:
            return candidate, samples
    return None, []


def discover_bundle(bundle_dir: str | os.PathLike[str]) -> BundleInputs:
    root = Path(bundle_dir).expanduser()
    if not root.is_dir():
        raise FileNotFoundError(f"walk bundle not found: {root}")
    gps_path, gps = _discover_gps(root)
    return BundleInputs(
        bundle_dir=root,
        video=_first_video(root),
        frames_dir=_first_frames_dir(root),
        gps_path=gps_path,
        gps=gps,
    )


# ---------------------------------------------------------------------------
# Geodesy + GPS Sim3 (COLMAP → ECEF)
# ---------------------------------------------------------------------------

def geodetic_to_ecef(lat: float, lon: float, alt_m: float = 0.0) -> np.ndarray:
    lat_r = math.radians(lat)
    lon_r = math.radians(lon)
    sin_lat, cos_lat = math.sin(lat_r), math.cos(lat_r)
    n = _WGS84_A / math.sqrt(1.0 - _WGS84_E2 * sin_lat * sin_lat)
    return np.array([
        (n + alt_m) * cos_lat * math.cos(lon_r),
        (n + alt_m) * cos_lat * math.sin(lon_r),
        (n * (1.0 - _WGS84_E2) + alt_m) * sin_lat,
    ], dtype=np.float64)


def camera_center_w2c(qvec: np.ndarray, tvec: np.ndarray) -> np.ndarray:
    """COLMAP world-to-camera (q, t) → camera center in world coords."""
    rot = _qvec_to_rotmat(qvec)
    return -rot.T @ np.asarray(tvec, dtype=np.float64)


def c2w_from_w2c(qvec: np.ndarray, tvec: np.ndarray) -> np.ndarray:
    rot = _qvec_to_rotmat(qvec)
    center = -rot.T @ np.asarray(tvec, dtype=np.float64)
    mat = np.eye(4, dtype=np.float64)
    mat[:3, :3] = rot.T
    mat[:3, 3] = center
    return mat


def _qvec_to_rotmat(qvec: np.ndarray) -> np.ndarray:
    qw, qx, qy, qz = [float(v) for v in qvec]
    return np.array([
        [1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw)],
        [2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw)],
        [2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy)],
    ], dtype=np.float64)


def _rotmat_to_qvec(rot: np.ndarray) -> np.ndarray:
    """Hamilton qwxyz from a rotation matrix (COLMAP convention)."""
    m = np.asarray(rot, dtype=np.float64)
    trace = float(np.trace(m))
    if trace > 0:
        s = 2.0 * math.sqrt(trace + 1.0)
        qw = 0.25 * s
        qx = (m[2, 1] - m[1, 2]) / s
        qy = (m[0, 2] - m[2, 0]) / s
        qz = (m[1, 0] - m[0, 1]) / s
    else:
        if m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
            s = 2.0 * math.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2])
            qw = (m[2, 1] - m[1, 2]) / s
            qx = 0.25 * s
            qy = (m[0, 1] + m[1, 0]) / s
            qz = (m[0, 2] + m[2, 0]) / s
        elif m[1, 1] > m[2, 2]:
            s = 2.0 * math.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2])
            qw = (m[0, 2] - m[2, 0]) / s
            qx = (m[0, 1] + m[1, 0]) / s
            qy = 0.25 * s
            qz = (m[1, 2] + m[2, 1]) / s
        else:
            s = 2.0 * math.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1])
            qw = (m[1, 0] - m[0, 1]) / s
            qx = (m[0, 2] + m[2, 0]) / s
            qy = (m[1, 2] + m[2, 1]) / s
            qz = 0.25 * s
    q = np.array([qw, qx, qy, qz], dtype=np.float64)
    if q[0] < 0:
        q = -q
    return q


def w2c_from_center(center: np.ndarray, rotation: np.ndarray | None = None) -> tuple[np.ndarray, np.ndarray]:
    rot = np.eye(3, dtype=np.float64) if rotation is None else np.asarray(rotation, dtype=np.float64)
    tvec = -rot @ np.asarray(center, dtype=np.float64)
    return _rotmat_to_qvec(rot), tvec


def pair_centers_to_gps(
    images: list[ColmapImage],
    gps: list[GpsSample],
) -> tuple[np.ndarray, np.ndarray] | None:
    """Return (colmap_xyz, ecef_xyz) correspondences, or None if <3 pairs."""
    if not images or not gps:
        return None
    centers = np.stack([camera_center_w2c(im.qvec, im.tvec) for im in images])
    ecef = np.stack([geodetic_to_ecef(s.lat, s.lon, s.alt_m) for s in gps])

    by_name = {}
    for sample in gps:
        if sample.name:
            stem = Path(str(sample.name)).stem
            by_name[stem] = sample
            by_name[str(sample.name)] = sample
    named_pairs: list[tuple[np.ndarray, np.ndarray]] = []
    if by_name:
        for image, center in zip(images, centers):
            sample = by_name.get(Path(image.name).stem) or by_name.get(image.name)
            if sample is None:
                continue
            named_pairs.append((center, geodetic_to_ecef(sample.lat, sample.lon, sample.alt_m)))
        if len(named_pairs) >= 3:
            src = np.stack([p[0] for p in named_pairs])
            dst = np.stack([p[1] for p in named_pairs])
            return src, dst

    image_times = _image_times(images)
    gps_times = [s.t for s in gps]
    if image_times is not None and all(t is not None for t in gps_times) and len(gps) >= 2:
        gt = np.array([float(t) for t in gps_times], dtype=np.float64)
        order = np.argsort(gt)
        gt, ecef_sorted = gt[order], ecef[order]
        sampled = np.vstack([
            np.array([
                np.interp(t, gt, ecef_sorted[:, axis])
                for axis in range(3)
            ])
            for t in image_times
        ])
        return centers, sampled

    if len(images) == len(gps):
        return centers, ecef

    if len(gps) >= 3 and len(images) >= 3:
        # Evenly sample the GPS polyline at image count — last-resort pairing
        # when clocks and names are absent (synthetic tracks, coarse JSONL).
        idx = np.linspace(0, len(gps) - 1, len(images))
        sampled = np.vstack([
            ecef[int(round(i))] if abs(i - round(i)) < 1e-9
            else (1 - (i - math.floor(i))) * ecef[int(math.floor(i))]
            + (i - math.floor(i)) * ecef[min(int(math.ceil(i)), len(ecef) - 1)]
            for i in idx
        ])
        return centers, sampled
    return None


def _image_times(images: list[ColmapImage]) -> list[float] | None:
    times: list[float] = []
    for image in images:
        stem = Path(image.name).stem
        digits = "".join(ch if ch.isdigit() or ch == "." else " " for ch in stem).split()
        if not digits:
            return None
        times.append(float(digits[-1]))
    return times


def fit_colmap_to_ecef(
    colmap_xyz: np.ndarray,
    ecef_xyz: np.ndarray,
) -> dict:
    scale, rotation, translation, rms = umeyama_sim3(colmap_xyz, ecef_xyz)
    matrix = np.eye(4, dtype=np.float64)
    matrix[:3, :3] = scale * rotation
    matrix[:3, 3] = translation
    return {
        "scale": scale,
        "rotation": rotation.tolist(),
        "translation_ecef_m": translation.tolist(),
        "matrix_4x4": matrix.tolist(),
        "rms_m": rms,
        "correspondences": int(len(colmap_xyz)),
    }


def build_poses_gps(model: ColmapModel, gps: list[GpsSample]) -> dict:
    payload = {
        "schema_version": SCHEMA_VERSION,
        "source_crs": "colmap",
        "target_crs": "EPSG:4978",
        "geographic_crs": "EPSG:4326",
        "transform": None,
        "anchor": None,
        "reason": None,
    }
    if not gps:
        payload["reason"] = "no_gps_track"
        return payload
    if model.empty:
        payload["reason"] = "no_colmap_poses"
        return payload
    paired = pair_centers_to_gps(model.images, gps)
    if paired is None:
        payload["reason"] = "insufficient_gps_correspondences"
        return payload
    src, dst = paired
    if len(src) < 3:
        payload["reason"] = "insufficient_gps_correspondences"
        return payload
    try:
        payload["transform"] = fit_colmap_to_ecef(src, dst)
    except Exception as exc:
        payload["reason"] = f"gps_fit_failed:{exc}"
        return payload
    payload["anchor"] = {
        "lat": gps[0].lat,
        "lon": gps[0].lon,
        "alt_m": gps[0].alt_m,
    }
    return payload


# ---------------------------------------------------------------------------
# COLMAP I/O (text + a minimal binary reader)
# ---------------------------------------------------------------------------

def write_colmap_text(model_dir: str | os.PathLike[str], model: ColmapModel) -> Path:
    dest = Path(model_dir)
    dest.mkdir(parents=True, exist_ok=True)
    cameras_path = dest / "cameras.txt"
    images_path = dest / "images.txt"
    points_path = dest / "points3D.txt"
    cameras_path.write_text(_format_cameras_txt(model.cameras), encoding="utf-8")
    images_path.write_text(_format_images_txt(model.images), encoding="utf-8")
    points_path.write_text(_format_points_txt(model.points), encoding="utf-8")
    return dest


def _format_cameras_txt(cameras: list[ColmapCamera]) -> str:
    lines = [
        "# Camera list with one line of data per camera:",
        "#   CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]",
        f"# Number of cameras: {len(cameras)}",
    ]
    for cam in cameras:
        params = " ".join(str(p) for p in cam.params)
        lines.append(f"{cam.camera_id} {cam.model} {cam.width} {cam.height} {params}")
    return "\n".join(lines) + "\n"


def _format_images_txt(images: list[ColmapImage]) -> str:
    lines = [
        "# Image list with two lines of data per image:",
        "#   IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME",
        "#   POINTS2D[] as (X, Y, POINT3D_ID)",
        f"# Number of images: {len(images)}",
    ]
    for image in images:
        q = " ".join(f"{v:.12g}" for v in image.qvec)
        t = " ".join(f"{v:.12g}" for v in image.tvec)
        lines.append(f"{image.image_id} {q} {t} {image.camera_id} {image.name}")
        if image.points2d:
            pts = " ".join(
                f"{x} {y} {pid}" for x, y, pid in image.points2d
            )
            lines.append(pts)
        else:
            lines.append("")
    return "\n".join(lines) + "\n"


def _format_points_txt(points: list[ColmapPoint]) -> str:
    lines = [
        "# 3D point list with one line of data per point:",
        "#   POINT3D_ID, X, Y, Z, R, G, B, ERROR, TRACK[] as (IMAGE_ID, POINT2D_IDX)",
        f"# Number of points: {len(points)}",
    ]
    for point in points:
        xyz = " ".join(f"{v:.12g}" for v in point.xyz)
        track = " ".join(f"{img} {idx}" for img, idx in point.track)
        extra = f" {track}" if track else ""
        lines.append(
            f"{point.point_id} {xyz} {point.rgb[0]} {point.rgb[1]} {point.rgb[2]} "
            f"{point.error:.12g}{extra}"
        )
    return "\n".join(lines) + "\n"


def _skip_comment(line: str) -> bool:
    stripped = line.strip()
    return not stripped or stripped.startswith("#")


def read_colmap_text(model_dir: str | os.PathLike[str]) -> ColmapModel:
    root = Path(model_dir)
    cameras: list[ColmapCamera] = []
    cameras_path = root / "cameras.txt"
    if cameras_path.is_file():
        for line in cameras_path.read_text(encoding="utf-8").splitlines():
            if _skip_comment(line):
                continue
            parts = line.split()
            cameras.append(ColmapCamera(
                camera_id=int(parts[0]),
                model=parts[1],
                width=int(parts[2]),
                height=int(parts[3]),
                params=[float(v) for v in parts[4:]],
            ))
    images: list[ColmapImage] = []
    images_path = root / "images.txt"
    if images_path.is_file():
        # COLMAP text stores two lines per image; a missing POINTS2D line
        # still occupies an empty record after the pose line.
        raw_lines = images_path.read_text(encoding="utf-8").splitlines()
        pose_pending = False
        current: ColmapImage | None = None
        for line in raw_lines:
            if line.startswith("#"):
                continue
            if not pose_pending:
                if not line.strip():
                    continue
                parts = line.split()
                current = ColmapImage(
                    image_id=int(parts[0]),
                    qvec=np.array([float(v) for v in parts[1:5]], dtype=np.float64),
                    tvec=np.array([float(v) for v in parts[5:8]], dtype=np.float64),
                    camera_id=int(parts[8]),
                    name=parts[9],
                )
                images.append(current)
                pose_pending = True
            else:
                if current is not None and line.strip():
                    nums = line.split()
                    pts = []
                    for j in range(0, len(nums) - 2, 3):
                        pts.append((float(nums[j]), float(nums[j + 1]), int(nums[j + 2])))
                    current.points2d = pts
                pose_pending = False
                current = None
    points: list[ColmapPoint] = []
    points_path = root / "points3D.txt"
    if points_path.is_file():
        for line in points_path.read_text(encoding="utf-8").splitlines():
            if _skip_comment(line):
                continue
            parts = line.split()
            track = []
            rest = parts[8:]
            for j in range(0, len(rest) - 1, 2):
                track.append((int(rest[j]), int(rest[j + 1])))
            points.append(ColmapPoint(
                point_id=int(parts[0]),
                xyz=np.array([float(parts[1]), float(parts[2]), float(parts[3])]),
                rgb=(int(parts[4]), int(parts[5]), int(parts[6])),
                error=float(parts[7]),
                track=track,
            ))
    return ColmapModel(cameras=cameras, images=images, points=points)


def _read_next(handle, fmt: str):
    size = struct.calcsize(fmt)
    buf = handle.read(size)
    if len(buf) != size:
        raise VidmapError("truncated COLMAP binary")
    return struct.unpack(fmt, buf)


def read_colmap_bin(model_dir: str | os.PathLike[str]) -> ColmapModel:
    root = Path(model_dir)
    cameras: list[ColmapCamera] = []
    with (root / "cameras.bin").open("rb") as handle:
        (n_cams,) = _read_next(handle, "<Q")
        for _ in range(n_cams):
            camera_id, model_id = _read_next(handle, "<ii")
            width, height = _read_next(handle, "<QQ")
            name, n_params = _COLMAP_CAMERA_MODELS.get(model_id, ("PINHOLE", 4))
            params = list(_read_next(handle, "<" + "d" * n_params))
            cameras.append(ColmapCamera(camera_id, name, int(width), int(height), params))
    images: list[ColmapImage] = []
    with (root / "images.bin").open("rb") as handle:
        (n_images,) = _read_next(handle, "<Q")
        for _ in range(n_images):
            (image_id,) = _read_next(handle, "<i")
            qvec = np.array(_read_next(handle, "<dddd"), dtype=np.float64)
            tvec = np.array(_read_next(handle, "<ddd"), dtype=np.float64)
            (camera_id,) = _read_next(handle, "<i")
            name_chars = []
            while True:
                ch = handle.read(1)
                if ch in (b"", b"\x00"):
                    break
                name_chars.append(ch.decode("utf-8"))
            (n_pts,) = _read_next(handle, "<Q")
            pts = []
            for _p in range(n_pts):
                x, y, pid = _read_next(handle, "<ddq")
                pts.append((x, y, int(pid)))
            images.append(ColmapImage(
                image_id=image_id, qvec=qvec, tvec=tvec,
                camera_id=camera_id, name="".join(name_chars), points2d=pts,
            ))
    points: list[ColmapPoint] = []
    points_bin = root / "points3D.bin"
    if points_bin.is_file():
        with points_bin.open("rb") as handle:
            (n_pts,) = _read_next(handle, "<Q")
            for _ in range(n_pts):
                (pid,) = _read_next(handle, "<Q")
                xyz = np.array(_read_next(handle, "<ddd"), dtype=np.float64)
                rgb = _read_next(handle, "<BBB")
                (error,) = _read_next(handle, "<d")
                (track_len,) = _read_next(handle, "<Q")
                track = []
                for _t in range(track_len):
                    img_id, idx = _read_next(handle, "<ii")
                    track.append((img_id, idx))
                points.append(ColmapPoint(int(pid), xyz, rgb, error, track))
    return ColmapModel(cameras=cameras, images=images, points=points)


def load_colmap_model(model_dir: str | os.PathLike[str]) -> ColmapModel | None:
    root = Path(model_dir)
    if not root.is_dir():
        return None
    if (root / "images.bin").is_file() and (root / "cameras.bin").is_file():
        return read_colmap_bin(root)
    if (root / "images.txt").is_file() and (root / "cameras.txt").is_file():
        return read_colmap_text(root)
    return None


def find_colmap_model(output_dir: Path) -> Path | None:
    for candidate in (
        output_dir / "colmap",
        output_dir / "run" / "rec",
        output_dir / "rec",
        output_dir,
    ):
        if (candidate / "images.bin").is_file() or (candidate / "images.txt").is_file():
            return candidate
    return None


# ---------------------------------------------------------------------------
# Quality + WorldMirror prior
# ---------------------------------------------------------------------------

def _intrinsics_matrix(camera: ColmapCamera) -> list[list[float]]:
    p = camera.params
    if camera.model in {"SIMPLE_PINHOLE", "SIMPLE_RADIAL", "SIMPLE_RADIAL_FISHEYE"}:
        f, cx, cy = p[0], p[1], p[2]
        fx = fy = f
    else:
        fx, fy, cx, cy = p[0], p[1], p[2], p[3]
    return [[fx, 0.0, cx], [0.0, fy, cy], [0.0, 0.0, 1.0]]


def prior_camera_from_colmap(model: ColmapModel) -> dict:
    cameras = {c.camera_id: c for c in model.cameras}
    extrinsics = []
    intrinsics = []
    for image in model.images:
        camera_id = Path(image.name).stem or str(image.image_id)
        extrinsics.append({
            "camera_id": camera_id,
            "matrix": c2w_from_w2c(image.qvec, image.tvec).tolist(),
        })
        cam = cameras.get(image.camera_id)
        if cam is not None:
            intrinsics.append({
                "camera_id": camera_id,
                "matrix": _intrinsics_matrix(cam),
            })
    return build_prior_cam_payload(extrinsics, intrinsics, source="vidmap")


def track_length_summary(model: ColmapModel) -> dict:
    lengths = [len(point.track) for point in model.points if point.track]
    if not lengths:
        return {
            "count": 0,
            "min": None,
            "max": None,
            "mean": None,
            "median": None,
        }
    arr = np.asarray(lengths, dtype=np.float64)
    return {
        "count": int(len(arr)),
        "min": int(arr.min()),
        "max": int(arr.max()),
        "mean": float(arr.mean()),
        "median": float(np.median(arr)),
    }


def infer_loop_closure(model: ColmapModel) -> bool:
    if len(model.images) < 4:
        return False
    centers = np.stack([camera_center_w2c(im.qvec, im.tvec) for im in model.images])
    step = np.linalg.norm(np.diff(centers, axis=0), axis=1).sum()
    if step <= 0:
        return False
    close = float(np.linalg.norm(centers[0] - centers[-1]))
    return close <= LOOP_CLOSE_FRACTION * step


def build_quality(
    *,
    status: str,
    reason: str | None,
    model: ColmapModel,
    inputs: BundleInputs,
    dry_run: bool,
    gpu_used: bool,
    loop_closure: bool | None = None,
) -> dict:
    keyframes = len(model.images)
    tracks = track_length_summary(model)
    if loop_closure is None:
        loop_closure = infer_loop_closure(model)
    nogo: list[str] = []
    if status != "ok":
        nogo.append(reason or status)
    if keyframes < MIN_KEYFRAMES_GO:
        nogo.append("too_few_keyframes")
    if tracks["count"] == 0 and status == "ok":
        nogo.append("no_sparse_tracks")
    go = status == "ok" and not nogo
    return {
        "schema_version": SCHEMA_VERSION,
        "status": status,
        "reason": reason,
        "keyframe_count": keyframes,
        "track_length": tracks,
        "loop_closure": bool(loop_closure),
        "go": go,
        "nogo_reasons": nogo,
        "gpu_used": gpu_used,
        "dry_run": dry_run,
        "input": {
            "video": str(inputs.video) if inputs.video else None,
            "frames_dir": str(inputs.frames_dir) if inputs.frames_dir else None,
            "gps_track": str(inputs.gps_path) if inputs.gps_path else None,
        },
    }


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------

def _empty_model() -> ColmapModel:
    return ColmapModel()


def _copy_or_write_colmap(src: Path | None, dest: Path, model: ColmapModel) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    if src is not None and src.resolve() != dest.resolve():
        for name in (
            "cameras.bin", "images.bin", "points3D.bin",
            "cameras.txt", "images.txt", "points3D.txt",
        ):
            file = src / name
            if file.is_file():
                (dest / name).write_bytes(file.read_bytes())
        if (dest / "images.bin").is_file() or (dest / "images.txt").is_file():
            return
    write_colmap_text(dest, model)


def _run_vidmap_cli(
    command: list[str],
    input_data: Path,
    run_dir: Path,
    *,
    job_id: str | int | None,
) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    argv = list(command)
    # python -m vidmap.run uses --input_data/--output; a custom VIDMAP_BIN
    # may already include those flags — only append the standard pair.
    joined = " ".join(argv)
    if "--input_data" not in joined and "--input-data" not in joined:
        argv.extend(["--input_data", str(input_data)])
    if "--output" not in joined:
        argv.extend(["--output", str(run_dir)])
    env = dict(os.environ)
    repo = os.environ.get("VIDMAP_REPO", "").strip()
    if repo:
        env["PYTHONPATH"] = (
            str(Path(repo).expanduser())
            + (os.pathsep + env["PYTHONPATH"] if env.get("PYTHONPATH") else "")
        )
    with GpuReservation(job_id=job_id, command=" ".join(argv)).acquire("vidmap"):
        proc = subprocess.run(argv, env=env, check=False)
    if proc.returncode != 0:
        raise VidmapError(f"vidmap CLI exited {proc.returncode}: {' '.join(argv)}")


def run_bundle(
    bundle_dir: str | os.PathLike[str],
    *,
    dry_run: bool = True,
    job_id: str | int | None = None,
    model: ColmapModel | None = None,
) -> VidmapResult:
    """Process one walk bundle. ``dry_run=True`` never takes the GPU lock."""
    assert_not_quarantined(bundle_dir)
    inputs = discover_bundle(bundle_dir)
    out = output_dir_for(inputs.bundle_dir)
    colmap_dest = colmap_dir_for(inputs.bundle_dir)
    out.mkdir(parents=True, exist_ok=True)

    command = vidmap_command()
    gpu_used = False
    status = "not_run"
    reason = "dry_run" if dry_run else None
    if command is None:
        reason = "vidmap_not_installed"

    loaded: ColmapModel | None = model
    found_model_dir: Path | None = None

    if not dry_run and command is not None:
        if inputs.input_data is None:
            status = "failed"
            reason = "no_video_or_frames"
        else:
            run_dir = out / "run"
            _run_vidmap_cli(command, inputs.input_data, run_dir, job_id=job_id)
            gpu_used = True
            found_model_dir = find_colmap_model(out)
            if found_model_dir is not None:
                loaded = load_colmap_model(found_model_dir)
            status = "ok" if loaded and not loaded.empty else "failed"
            if status == "failed" and reason is None:
                reason = "vidmap_no_colmap_model"
    else:
        if loaded is None:
            found_model_dir = find_colmap_model(out)
            if found_model_dir is not None:
                loaded = load_colmap_model(found_model_dir)
        if dry_run:
            status = "not_run"
            if command is None:
                reason = "vidmap_not_installed"
            elif reason is None:
                reason = "dry_run"
        elif command is None:
            status = "not_run"
            reason = "vidmap_not_installed"

    if loaded is None:
        loaded = _empty_model()

    _copy_or_write_colmap(found_model_dir, colmap_dest, loaded)

    poses_gps = build_poses_gps(loaded, inputs.gps)
    prior_camera = prior_camera_from_colmap(loaded)
    validate_prior_cam_payload(prior_camera)
    quality = build_quality(
        status=status,
        reason=reason,
        model=loaded,
        inputs=inputs,
        dry_run=dry_run,
        gpu_used=gpu_used,
    )

    (out / QUALITY_NAME).write_text(json.dumps(quality, indent=2) + "\n", encoding="utf-8")
    (out / POSES_GPS_NAME).write_text(json.dumps(poses_gps, indent=2) + "\n", encoding="utf-8")
    (out / PRIOR_CAM_NAME).write_text(json.dumps(prior_camera, indent=2) + "\n", encoding="utf-8")

    return VidmapResult(
        bundle_dir=inputs.bundle_dir,
        output_dir=out,
        colmap_dir=colmap_dest,
        quality=quality,
        poses_gps=poses_gps,
        prior_camera=prior_camera,
        status=status,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", help="Walk bundle directory (video and/or frames + optional GPS)")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=True,
        help="Write the output schema without running VidMap (default)",
    )
    parser.add_argument(
        "--run",
        action="store_true",
        help="Execute the VidMap CLI (GPU-serial; requires VIDMAP_BIN or VIDMAP_REPO)",
    )
    parser.add_argument("--job-id", default=None, help="Optional GPU lock job id")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    dry_run = not args.run
    try:
        result = run_bundle(args.bundle, dry_run=dry_run, job_id=args.job_id)
    except QuarantinedBundleError as exc:
        print(f"vidmap_runner: {exc}", file=sys.stderr)
        return 3
    print(json.dumps({
        "status": result.status,
        "output": str(result.output_dir),
        "quality": result.quality,
        "poses_gps_reason": result.poses_gps.get("reason"),
        "prior_cam_schema": PRIOR_CAM_SCHEMA,
    }, indent=2))
    if args.run and result.status != "ok":
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
