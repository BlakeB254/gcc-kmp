"""CPU-only VidMap P0 contract: schema, GPS Sim3, quarantine, WorldMirror prior."""
from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
import pytest

import vidmap_runner
from vidmap_runner import (
    ColmapCamera,
    ColmapImage,
    ColmapModel,
    QuarantinedBundleError,
    geodetic_to_ecef,
    run_bundle,
    w2c_from_center,
)
from world_mirror_client import (
    build_pipeline_argv,
    extract_extrinsic_matrices,
    resolve_prior_cam_path,
    validate_prior_cam_payload,
)


def _clear_vidmap_env(monkeypatch):
    monkeypatch.delenv("VIDMAP_BIN", raising=False)
    monkeypatch.delenv("VIDMAP_REPO", raising=False)


def _write_gps_jsonl(path: Path, samples: list[dict]) -> None:
    path.write_text("".join(json.dumps(s) + "\n" for s in samples), encoding="utf-8")


def _rotation_z(deg: float) -> np.ndarray:
    rad = math.radians(deg)
    c, s = math.cos(rad), math.sin(rad)
    return np.array([[c, -s, 0.0], [s, c, 0.0], [0.0, 0.0, 1.0]], dtype=np.float64)


def _synthetic_gps():
    return [
        {"name": "frame_0001.jpg", "lat": 41.8600, "lon": -87.7200, "alt": 180.0, "t": 0.0},
        {"name": "frame_0002.jpg", "lat": 41.8610, "lon": -87.7200, "alt": 180.0, "t": 1.0},
        {"name": "frame_0003.jpg", "lat": 41.8600, "lon": -87.7185, "alt": 181.0, "t": 2.0},
        {"name": "frame_0004.jpg", "lat": 41.8612, "lon": -87.7188, "alt": 181.0, "t": 3.0},
    ]


def _model_from_ecef(ecef: np.ndarray, scale: float, rotation: np.ndarray, translation: np.ndarray) -> ColmapModel:
    """Invert dst = s R src + t so COLMAP centers reconstruct the GPS ECEF."""
    src = (1.0 / scale) * (rotation.T @ (ecef - translation).T).T
    camera = ColmapCamera(1, "PINHOLE", 640, 480, [500.0, 500.0, 320.0, 240.0])
    images = []
    for i, center in enumerate(src, start=1):
        qvec, tvec = w2c_from_center(center)
        images.append(ColmapImage(
            image_id=i,
            qvec=qvec,
            tvec=tvec,
            camera_id=1,
            name=f"frame_{i:04d}.jpg",
        ))
    return ColmapModel(cameras=[camera], images=images)


def test_dry_run_writes_schema_without_vidmap(tmp_path, monkeypatch):
    _clear_vidmap_env(monkeypatch)
    bundle = tmp_path / "route_nl_civic_spine_001"
    bundle.mkdir()
    (bundle / "frames").mkdir()
    (bundle / "frames" / "frame_0001.jpg").write_bytes(b"not-a-real-jpeg")

    result = run_bundle(bundle, dry_run=True)

    out = bundle / "rec" / "vidmap"
    assert result.output_dir == out
    quality = json.loads((out / "quality.json").read_text())
    poses = json.loads((out / "poses_gps.json").read_text())
    prior = json.loads((out / "prior_camera.json").read_text())
    assert quality["schema_version"] == 1
    assert quality["status"] == "not_run"
    assert quality["reason"] == "vidmap_not_installed"
    assert quality["dry_run"] is True
    assert quality["gpu_used"] is False
    assert quality["go"] is False
    assert "vidmap_not_installed" in quality["nogo_reasons"]
    assert "keyframe_count" in quality
    assert {"count", "min", "max", "mean", "median"} <= set(quality["track_length"])
    assert "loop_closure" in quality
    assert poses["transform"] is None
    assert poses["reason"] == "no_gps_track"
    assert poses["target_crs"] == "EPSG:4978"
    assert poses["geographic_crs"] == "EPSG:4326"
    validate_prior_cam_payload(prior)
    assert (out / "colmap" / "cameras.txt").is_file()
    assert (out / "colmap" / "images.txt").is_file()
    assert (out / "colmap" / "points3D.txt").is_file()


def test_gps_fit_produces_transform_from_synthetic_track(tmp_path, monkeypatch):
    _clear_vidmap_env(monkeypatch)
    bundle = tmp_path / "route_nl_homan_square_002"
    bundle.mkdir()
    samples = _synthetic_gps()
    _write_gps_jsonl(bundle / "gps.jsonl", samples)

    ecef = np.stack([
        geodetic_to_ecef(s["lat"], s["lon"], s["alt"]) for s in samples
    ])
    scale, rotation = 2.0, _rotation_z(25.0)
    translation = ecef.mean(axis=0) + np.array([15.0, -8.0, 4.0])
    model = _model_from_ecef(ecef, scale, rotation, translation)

    result = run_bundle(bundle, dry_run=True, model=model)
    poses = json.loads((result.output_dir / "poses_gps.json").read_text())
    assert poses["reason"] is None
    transform = poses["transform"]
    assert transform is not None
    assert transform["correspondences"] == 4
    assert abs(transform["scale"] - scale) < 1e-6
    assert transform["rms_m"] < 1e-6
    got_r = np.asarray(transform["rotation"])
    assert np.allclose(got_r, rotation, atol=1e-6)
    assert np.allclose(transform["translation_ecef_m"], translation, atol=1e-5)

    recovered = (
        transform["scale"] * (got_r @ np.stack([
            vidmap_runner.camera_center_w2c(im.qvec, im.tvec) for im in model.images
        ]).T).T
        + np.asarray(transform["translation_ecef_m"])
    )
    assert np.allclose(recovered, ecef, atol=1e-5)


def test_quarantine_name_is_rejected(tmp_path, monkeypatch):
    _clear_vidmap_env(monkeypatch)
    bundle = tmp_path / "rayban_wwgo3126_douglass_park"
    bundle.mkdir()
    with pytest.raises(QuarantinedBundleError, match="rayban_wwgo3126_douglass_park"):
        run_bundle(bundle, dry_run=True)
    assert not (bundle / "rec").exists()


def test_worldmirror_prior_cam_matches_client_shape(tmp_path, monkeypatch):
    _clear_vidmap_env(monkeypatch)
    bundle = tmp_path / "route_nl_douglas_blvd_003"
    bundle.mkdir()
    samples = _synthetic_gps()
    _write_gps_jsonl(bundle / "gps.jsonl", samples)
    ecef = np.stack([geodetic_to_ecef(s["lat"], s["lon"], s["alt"]) for s in samples])
    model = _model_from_ecef(ecef, 1.0, np.eye(3), ecef.mean(axis=0))

    result = run_bundle(bundle, dry_run=True, model=model)
    payload = json.loads((result.output_dir / "prior_camera.json").read_text())
    validate_prior_cam_payload(payload)
    cameras = extract_extrinsic_matrices(payload)
    # reconstruct_session reads camera_params.json the same way.
    assert cameras == [c["matrix"] for c in payload.get("extrinsics", [])]
    assert len(cameras) == 4
    assert all(len(m) == 4 and all(len(row) == 4 for row in m) for m in cameras)
    assert len(payload["intrinsics"]) == 4
    assert all(len(c["matrix"]) == 3 for c in payload["intrinsics"])
    assert payload["source"] == "vidmap"

    approx = bundle / "prior_camera.json"
    approx.write_text(json.dumps({"schema_version": "1.0", "frames": []}), encoding="utf-8")
    resolved = resolve_prior_cam_path(bundle)
    assert resolved == result.output_dir / "prior_camera.json"
    argv = build_pipeline_argv(
        bundle / "frames",
        tmp_path / "wm-out",
        bundle_dir=bundle,
    )
    assert "--prior_cam_path" in argv
    assert str(resolved) in argv


def test_empty_vidmap_prior_falls_back_to_approx(tmp_path, monkeypatch):
    _clear_vidmap_env(monkeypatch)
    bundle = tmp_path / "route_nl_fallback_004"
    bundle.mkdir()
    approx = bundle / "prior_camera.json"
    approx.write_text(json.dumps({"schema_version": "1.0", "note": "approx"}), encoding="utf-8")
    run_bundle(bundle, dry_run=True)
    # Dry-run without poses writes a valid but empty WorldMirror prior — that
    # is not usable cameras, so a recon job must keep the approx file.
    assert resolve_prior_cam_path(bundle) == approx
    argv = build_pipeline_argv("images", "out", bundle_dir=bundle)
    assert argv[argv.index("--prior_cam_path") + 1] == str(approx)


def test_dry_run_does_not_touch_gpu_or_network(tmp_path, monkeypatch):
    _clear_vidmap_env(monkeypatch)

    class _BoomReservation:
        def __init__(self, *args, **kwargs):
            raise AssertionError("GPU reservation constructed")

    def _boom_request(*args, **kwargs):
        raise AssertionError("network called")

    monkeypatch.setattr(vidmap_runner, "GpuReservation", _BoomReservation)
    monkeypatch.setattr("requests.post", _boom_request)
    monkeypatch.setattr("requests.get", _boom_request)

    bundle = tmp_path / "route_nl_no_gpu_005"
    bundle.mkdir()
    run_bundle(bundle, dry_run=True)
