import hashlib
import io
import json
import struct
from fractions import Fraction
from types import SimpleNamespace

import av
import liblzfse
import numpy as np
import pytest

import r2_store
from fuse_cell import (
    MIN_SESSIONS_FOR_ACTIVATION,
    SessionFusionInput,
    _fuse_one_chunk,
    _group_chunks_by_index,
    _session_fusion_input_from_manifest,
    fuse_sessions,
)


def _scene_points(seed: int, n_points: int = 2000) -> np.ndarray:
    rng = np.random.default_rng(seed)
    ground = np.stack(
        [rng.uniform(-5, 5, n_points), np.zeros(n_points), rng.uniform(-5, 5, n_points)], axis=1
    )
    cluster = rng.normal(loc=[1.0, 0.5, 1.0], scale=[0.2, 0.4, 0.2], size=(n_points // 3, 3))
    return np.vstack([ground, cluster])


def _session(session_id: str, seed: int, translation=(0.0, 0.0, 0.0), rotation_deg=0.0, anchor_lat=41.85, anchor_lng=-87.65) -> SessionFusionInput:
    points = _scene_points(seed)
    theta = np.radians(rotation_deg)
    rot = np.array([[np.cos(theta), 0, np.sin(theta)], [0, 1, 0], [-np.sin(theta), 0, np.cos(theta)]])
    points = points @ rot.T + np.array(translation)
    colors = np.tile([0.5, 0.5, 0.5], (points.shape[0], 1))
    return SessionFusionInput(
        session_id=session_id,
        device_id="device-a",
        points=points,
        colors=colors,
        anchor_lat=anchor_lat,
        anchor_lng=anchor_lng,
        anchor_local_xyz=np.array(translation),
    )


def test_first_session_always_accepted_and_not_yet_activated():
    result = fuse_sessions([_session("s1", seed=1)])
    assert result.accepted_session_ids == ["s1"]
    assert result.rejected_session_ids == []
    assert not result.activated  # 1 session < MIN_SESSIONS_FOR_ACTIVATION


def test_session_transforms_recorded_for_accepted_sessions_only():
    sessions = [
        _session("s1", seed=1),
        _session("s2", seed=1, translation=(0.1, 0.0, 0.05)),
        _session("bad", seed=99, translation=(80.0, 0.0, 80.0), anchor_lat=41.90, anchor_lng=-87.60),
    ]
    result = fuse_sessions(sessions)
    assert set(result.session_transforms.keys()) == {"s1", "s2"}
    assert "bad" not in result.session_transforms
    for transform in result.session_transforms.values():
        assert transform.shape == (4, 4)
    # First session in a fresh cell is always identity (nothing to align against yet).
    assert np.allclose(result.session_transforms["s1"], np.eye(4))


def test_activation_requires_minimum_independent_sessions():
    # Same underlying scene, tiny consistent offsets -- representative of
    # several real people scanning the same physical spot within normal GPS
    # noise, not an adversarial misalignment stress test (see registration.py's
    # documented ground-plane-degeneracy limitation for that).
    sessions = [
        _session("s1", seed=1),
        _session("s2", seed=1, translation=(0.1, 0.0, 0.05)),
        _session("s3", seed=1, translation=(-0.05, 0.0, 0.1)),
    ]
    assert len(sessions) == MIN_SESSIONS_FOR_ACTIVATION
    result = fuse_sessions(sessions)
    assert len(result.accepted_session_ids) == MIN_SESSIONS_FOR_ACTIVATION
    assert result.activated
    assert result.merged_points.shape[0] > 0


def test_an_unrelated_session_is_rejected_not_silently_merged():
    sessions = [
        _session("s1", seed=1),
        _session("intruder", seed=99, translation=(80.0, 0.0, 80.0), anchor_lat=41.90, anchor_lng=-87.60),
    ]
    result = fuse_sessions(sessions)
    assert "s1" in result.accepted_session_ids
    assert "intruder" in result.rejected_session_ids
    assert "intruder" not in result.accepted_session_ids


def test_activation_count_excludes_rejected_sessions():
    sessions = [
        _session("s1", seed=1),
        _session("s2", seed=1, translation=(0.1, 0.0, 0.05)),
        _session("bad", seed=99, translation=(80.0, 0.0, 80.0), anchor_lat=41.90, anchor_lng=-87.60),
    ]
    result = fuse_sessions(sessions)
    assert len(result.accepted_session_ids) == 2
    assert not result.activated  # rejection means only 2 of 3 count toward the gate


def test_group_chunks_by_index_groups_by_kind_from_key_extension():
    keys = [
        "raw/1:2/sess-a/capture/chunk-000.mov",
        "raw/1:2/sess-a/capture/chunk-000.jsonl",
        "raw/1:2/sess-a/capture/chunk-000.depth",
        "raw/1:2/sess-a/capture/chunk-001.jsonl",  # thermal-.serious chunk: sidecar-only, no video/depth
        "raw/1:2/sess-a/photo0.jpg",  # unrelated key, not a chunk at all -- must be ignored
    ]
    grouped = _group_chunks_by_index(keys)
    assert grouped[0] == {
        "mov": "raw/1:2/sess-a/capture/chunk-000.mov",
        "jsonl": "raw/1:2/sess-a/capture/chunk-000.jsonl",
        "depth": "raw/1:2/sess-a/capture/chunk-000.depth",
    }
    assert grouped[1] == {"jsonl": "raw/1:2/sess-a/capture/chunk-001.jsonl"}
    assert len(grouped) == 2


def test_group_chunks_by_index_accepts_real_bin_depth_extension():
    """Real uploads use .bin for depth (verified against R2 2026-07-10),
    despite ArSessionRecorder's doc-comment saying .depth -- both must land
    under the 'depth' kind."""
    keys = [
        "raw/464650:-721828/env-x/capture/chunk-0.mov",
        "raw/464650:-721828/env-x/capture/chunk-0.jsonl",
        "raw/464650:-721828/env-x/capture/chunk-0.bin",
    ]
    grouped = _group_chunks_by_index(keys)
    assert grouped[0] == {
        "mov": "raw/464650:-721828/env-x/capture/chunk-0.mov",
        "jsonl": "raw/464650:-721828/env-x/capture/chunk-0.jsonl",
        "depth": "raw/464650:-721828/env-x/capture/chunk-0.bin",
    }


def test_group_chunks_by_index_keeps_confidence_separate_from_legacy_depth():
    keys = [
        "raw/cell/session/capture/chunk-3.bin",
        "raw/cell/session/capture/chunk-3.confidence",
    ]

    grouped = _group_chunks_by_index(keys)

    assert grouped[3]["depth"].endswith("chunk-3.bin")
    assert grouped[3]["confidence"].endswith("chunk-3.confidence")


def test_depth_confidence_mask_removes_only_low_confidence_pixels():
    from fuse_cell import _apply_depth_confidence

    depth = np.array([[1.0, 2.0], [3.0, 4.0]], dtype=np.float32)
    confidence = np.array([[0, 1], [2, 0]], dtype=np.uint8)

    filtered = _apply_depth_confidence(depth, confidence)

    assert np.isnan(filtered[0, 0])
    assert filtered[0, 1] == 2.0
    assert filtered[1, 0] == 3.0
    assert np.isnan(filtered[1, 1])


# --- Real-format synthetic chunk builders (mirrors tests/test_chunk_io.py's
# fixtures -- kept local to this file rather than shared, since each exists
# to validate a different layer: test_chunk_io tests the decoders in
# isolation, this tests fuse_cell's orchestration consuming them). ---

def _column_major_flatten(matrix: np.ndarray) -> list[float]:
    return matrix.flatten(order="F").tolist()


def _build_sidecar_bytes(source_wh: tuple[int, int], video_wh: tuple[int, int], frames: list[dict]) -> bytes:
    header = {
        "schema": 1, "sessionId": "s1", "chunk": 0,
        "sourceWidth": source_wh[0], "sourceHeight": source_wh[1],
        "videoWidth": video_wh[0], "videoHeight": video_wh[1],
    }
    lines = [json.dumps(header)] + [json.dumps(f) for f in frames]
    return ("\n".join(lines) + "\n").encode("utf-8")


def _build_depth_bytes(records: list[tuple[float, np.ndarray]]) -> bytes:
    out = bytearray()
    for t, depth in records:
        height, width = depth.shape
        bytes_per_row = width * 4
        compressed = liblzfse.compress(depth.astype("<f4").tobytes())
        body = struct.pack("<dIIII", t, width, height, bytes_per_row, 0) + struct.pack("<I", len(compressed)) + compressed
        out += struct.pack("<I", len(body)) + body
    return bytes(out)


def _build_mov_bytes(frames_with_colors: list[tuple[float, tuple[int, int, int]]], width: int, height: int) -> bytes:
    buf = io.BytesIO()
    container = av.open(buf, mode="w", format="mp4")
    stream = container.add_stream("h264", rate=30)
    stream.width, stream.height = width, height
    stream.pix_fmt = "yuv420p"
    stream.codec_context.time_base = Fraction(1, 90000)
    for t, color in frames_with_colors:
        frame = av.VideoFrame.from_ndarray(np.full((height, width, 3), color, dtype=np.uint8), format="rgb24").reformat(format="yuv420p")
        frame.pts = int(t * 90000)
        for packet in stream.encode(frame):
            container.mux(packet)
    for packet in stream.encode():
        container.mux(packet)
    container.close()
    return buf.getvalue()


def test_fuse_one_chunk_builds_points_from_real_capture_format():
    width, height = 8, 8
    identity_transform = _column_major_flatten(np.eye(4))
    identity_intrinsics = _column_major_flatten(np.array([[10.0, 0, 0], [0, 10.0, 0], [4.0, 4.0, 1.0]]))
    sidecar = _build_sidecar_bytes(
        source_wh=(width, height), video_wh=(width, height),
        frames=[{"t": 0.5, "transform": identity_transform, "intrinsics": identity_intrinsics, "tracking": 0, "thermal": 0}],
    )
    depth = _build_depth_bytes([(0.5, np.full((height, width), 2.0, dtype=np.float32))])
    video = _build_mov_bytes([(0.5, (100, 150, 200))], width, height)

    clouds = _fuse_one_chunk(video, sidecar, depth)

    assert len(clouds) == 1
    assert clouds[0].points.shape[0] == width * height
    assert np.allclose(clouds[0].points[:, 2], 2.0, atol=1e-5)


def test_session_fusion_input_from_manifest_end_to_end(monkeypatch):
    width, height = 8, 8
    identity_transform = _column_major_flatten(np.eye(4))
    identity_intrinsics = _column_major_flatten(np.array([[10.0, 0, 0], [0, 10.0, 0], [4.0, 4.0, 1.0]]))
    sidecar = _build_sidecar_bytes(
        source_wh=(width, height), video_wh=(width, height),
        frames=[{"t": 0.5, "transform": identity_transform, "intrinsics": identity_intrinsics, "tracking": 0, "thermal": 0}],
    )
    depth = _build_depth_bytes([(0.5, np.full((height, width), 2.0, dtype=np.float32))])
    video = _build_mov_bytes([(0.5, (100, 150, 200))], width, height)

    fake_r2 = {
        "raw/1:2/sess-a/capture/chunk-000.mov": video,
        "raw/1:2/sess-a/capture/chunk-000.jsonl": sidecar,
        "raw/1:2/sess-a/capture/chunk-000.depth": depth,
    }
    monkeypatch.setattr(r2_store, "download_bytes", lambda key: fake_r2[key])

    manifest_obj = r2_store.ManifestObject(
        key="raw/1:2/deviceA/sess-a-manifest.json",
        manifest={
            "sessionId": "sess-a",
            "deviceId": "deviceA",
            "path": [{"lat": 41.85, "lng": -87.65, "atMs": 1000}],
            "captureChunks": list(fake_r2.keys()),
        },
    )

    result = _session_fusion_input_from_manifest(manifest_obj)

    assert result is not None
    assert result.session_id == "sess-a"
    assert result.points.shape[0] > 0
    assert result.anchor_lat == 41.85
    assert result.anchor_lng == -87.65
    assert np.allclose(result.anchor_local_xyz, [0.0, 0.0, 0.0])  # identity transform's translation


def test_session_fusion_input_from_manifest_none_without_capture_chunks():
    manifest_obj = r2_store.ManifestObject(
        key="raw/1:2/deviceA/sess-b-manifest.json",
        manifest={"sessionId": "sess-b", "deviceId": "deviceA", "path": [{"lat": 41.85, "lng": -87.65, "atMs": 1000}]},
    )
    assert _session_fusion_input_from_manifest(manifest_obj) is None


# --- Task 3 (2026-07-10 spec): run-versioned publishes + produced-asset list ---

def _identity_frame_fixture():
    width, height = 8, 8
    identity_transform = _column_major_flatten(np.eye(4))
    identity_intrinsics = _column_major_flatten(np.array([[10.0, 0, 0], [0, 10.0, 0], [4.0, 4.0, 1.0]]))
    sidecar = _build_sidecar_bytes(
        source_wh=(width, height), video_wh=(width, height),
        frames=[{"t": 0.5, "transform": identity_transform, "intrinsics": identity_intrinsics, "tracking": 0, "thermal": 0}],
    )
    depth = _build_depth_bytes([(0.5, np.full((height, width), 2.0, dtype=np.float32))])
    video = _build_mov_bytes([(0.5, (100, 150, 200))], width, height)
    return {
        "raw/1:2/sess-a/capture/chunk-000.mov": video,
        "raw/1:2/sess-a/capture/chunk-000.jsonl": sidecar,
        "raw/1:2/sess-a/capture/chunk-000.bin": depth,
    }


def _mock_cell_with_one_session(monkeypatch, run_version: int) -> dict[str, bytes]:
    fake_r2 = _identity_frame_fixture()
    manifest_obj = r2_store.ManifestObject(
        key="raw/1:2/deviceA/sess-a-manifest.json",
        manifest={
            "sessionId": "sess-a",
            "deviceId": "deviceA",
            "path": [{"lat": 41.85, "lng": -87.65, "atMs": 1000}],
            "captureChunks": list(fake_r2.keys()),
        },
    )
    monkeypatch.setattr(r2_store, "list_manifests_for_cell", lambda cell: [manifest_obj])
    monkeypatch.setattr(r2_store, "download_bytes", lambda key: fake_r2[key])
    monkeypatch.setattr(r2_store, "next_run_version_for_cell", lambda cell: run_version)
    uploaded: dict[str, bytes] = {}
    monkeypatch.setattr(r2_store, "upload_bytes", lambda key, data, content_type: uploaded.__setitem__(key, data))
    return uploaded


def test_next_run_version_for_cell_spans_asset_types(monkeypatch):
    """One version directory per RUN, shared by every asset type -- max
    version across ALL existing asset types + 1, not per-type."""
    class FakeClient:
        def list_objects_v2(self, **kwargs):
            return {"Contents": [
                {"Key": "reconstructed/1:2/1/pointcloud.ply"},
                {"Key": "reconstructed/1:2/2/splat.ply"},
            ]}
    monkeypatch.setattr(r2_store, "client", lambda: FakeClient())
    monkeypatch.setattr(r2_store, "bucket_name", lambda: "test-bucket")
    assert r2_store.next_run_version_for_cell("1:2") == 3


def test_fuse_cell_records_produced_assets_with_run_version(monkeypatch):
    from fuse_cell import fuse_cell

    uploaded = _mock_cell_with_one_session(monkeypatch, run_version=5)
    result = fuse_cell("1:2", api_base_url=None, train_splat=False)

    assert len(result.assets) == 1
    asset = result.assets[0]
    assert asset.asset_type == "pointcloud"
    assert asset.fmt == "ply"
    assert asset.version == 5
    assert asset.r2_key == "reconstructed/1:2/5/pointcloud.ply"
    assert asset.r2_key in uploaded
    assert asset.data == uploaded[asset.r2_key]


def test_verified_candidate_stages_complete_immutable_bundle(monkeypatch, tmp_path):
    """Catches publication omitting review evidence or writing outside the candidate prefix."""
    import fuse_cell as fuse_cell_module

    files = {}
    for name, payload in {
        "balanced.ply": b"balanced-ply",
        "frame-audit.json": b'{"selected":12}',
        "cleanup-report.json": b'{"policy":"balanced"}',
        "validation-metrics.json": b'{"imageSimilarity":0.95}',
        "validation-contact-sheet.jpg": b"jpeg",
    }.items():
        path = tmp_path / name
        path.write_bytes(payload)
        files[name] = path
    candidate = SimpleNamespace(
        balanced=files["balanced.ply"],
        publishable=files["balanced.ply"],
        publishable_policy="balanced",
        balanced_bytes=b"balanced-ply",
        frame_audit=files["frame-audit.json"],
        cleanup_report=files["cleanup-report.json"],
    )
    manifest_json = '{"decision":{"publish":true,"reasons":[]},"kind":"verified"}'
    manifest = SimpleNamespace(
        to_json=lambda: manifest_json,
        asset_sha256=hashlib.sha256(b"balanced-ply").hexdigest(),
    )
    uploaded = {}
    monkeypatch.setattr(r2_store, "object_exists", lambda _key: False)
    monkeypatch.setattr(
        r2_store,
        "upload_bytes",
        lambda key, data, content_type: uploaded.__setitem__(key, (data, content_type)),
    )

    staged = fuse_cell_module.stage_verified_candidate_assets(
        "1:2",
        7,
        candidate,
        manifest,
        web_asset=b"web-spz",
        web_format="spz",
        validation_metrics=files["validation-metrics.json"],
        contact_sheet=files["validation-contact-sheet.jpg"],
    )

    prefix = "reconstructed/1:2/7/verified-candidate/"
    assert set(uploaded) == {
        prefix + "verified.ply",
        prefix + "verified.spz",
        prefix + "manifest.json",
        prefix + "frame-audit.json",
        prefix + "cleanup-report.json",
        prefix + "validation-metrics.json",
        prefix + "validation-contact-sheet.jpg",
        prefix + "checksums.json",
    }
    checksums = json.loads(uploaded[prefix + "checksums.json"][0])
    assert set(checksums) == set(uploaded) - {prefix + "checksums.json"}
    assert staged.r2_key == prefix + "verified.ply"
    assert staged.artifact_keys == tuple(uploaded)


def test_verified_candidate_retry_refuses_to_overwrite_different_bytes(
    monkeypatch, tmp_path
):
    """Catches a version retry mutating an already staged immutable object."""
    import fuse_cell as fuse_cell_module

    candidate_path = tmp_path / "balanced.ply"
    audit_path = tmp_path / "frame-audit.json"
    cleanup_path = tmp_path / "cleanup-report.json"
    metrics_path = tmp_path / "validation-metrics.json"
    contact_path = tmp_path / "validation-contact-sheet.jpg"
    for path in (candidate_path, audit_path, cleanup_path, metrics_path, contact_path):
        path.write_bytes(path.name.encode())
    candidate = SimpleNamespace(
        balanced=candidate_path,
        publishable=candidate_path,
        publishable_policy="balanced",
        balanced_bytes=candidate_path.read_bytes(),
        frame_audit=audit_path,
        cleanup_report=cleanup_path,
    )
    manifest = SimpleNamespace(
        to_json=lambda: '{"kind":"verified"}',
        asset_sha256=hashlib.sha256(candidate_path.read_bytes()).hexdigest(),
    )
    monkeypatch.setattr(r2_store, "object_exists", lambda _key: True)
    monkeypatch.setattr(r2_store, "download_bytes", lambda _key: b"different-generation")
    monkeypatch.setattr(
        r2_store,
        "upload_bytes",
        lambda *_args, **_kwargs: pytest.fail("immutable object was overwritten"),
    )

    with pytest.raises(ValueError, match="immutable verified candidate key"):
        fuse_cell_module.stage_verified_candidate_assets(
            "1:2",
            7,
            candidate,
            manifest,
            web_asset=b"web",
            web_format="spz",
            validation_metrics=metrics_path,
            contact_sheet=contact_path,
        )


def test_fuse_cell_without_api_base_url_never_calls_http(monkeypatch):
    import fuse_cell as fuse_cell_module

    _mock_cell_with_one_session(monkeypatch, run_version=1)

    def _boom(*args, **kwargs):
        raise AssertionError("HTTP provenance must not be called when api_base_url is None")

    monkeypatch.setattr(fuse_cell_module.requests, "post", _boom)
    result = fuse_cell_module.fuse_cell("1:2", api_base_url=None, train_splat=False)
    assert result.assets  # it still published to R2


# --- Task 7 (splat_skip_reason): trainer (bytes | None, reason | None) tuple ---

def test_fuse_cell_splat_skip_reason_disabled_when_train_splat_off(monkeypatch):
    from fuse_cell import fuse_cell

    _mock_cell_with_one_session(monkeypatch, run_version=1)
    result = fuse_cell("1:2", api_base_url=None, train_splat=False)
    assert result.splat_skip_reason == "disabled"
    assert not any(a.asset_type == "splat" for a in result.assets)


def test_fuse_cell_splat_skip_reason_records_trainer_failure(monkeypatch):
    from fuse_cell import fuse_cell
    import gsplat_runner

    _mock_cell_with_one_session(monkeypatch, run_version=1)
    monkeypatch.setattr(gsplat_runner, "train_gsplat_for_cell", lambda *a, **k: (None, "training-failed"))
    result = fuse_cell("1:2", api_base_url=None, train_splat=True, require_activation_for_splat=False)
    assert result.splat_skip_reason == "training-failed"
    assert not any(a.asset_type == "splat" for a in result.assets)


def test_fuse_cell_splat_skip_reason_none_when_splat_produced(monkeypatch):
    from fuse_cell import fuse_cell
    import gsplat_runner
    from types import SimpleNamespace

    _mock_cell_with_one_session(monkeypatch, run_version=1)
    candidate = SimpleNamespace(balanced_bytes=b"fake-splat-ply")
    monkeypatch.setattr(
        gsplat_runner, "train_gsplat_for_cell", lambda *a, **k: (candidate, None)
    )
    result = fuse_cell("1:2", api_base_url=None, train_splat=True, require_activation_for_splat=False)
    assert result.splat_skip_reason is None
    splat_asset = next(asset for asset in result.assets if asset.asset_type == "splat")
    assert splat_asset.data == b"fake-splat-ply"


# ── World-registration path (scan-splat-registration-contract, PR #149) ──

def _geo_events(lat0=41.85, lng0=-87.65, offset_e=0.0, offset_n=0.0, n=6):
    """Events for a session whose AR frame coincides with ENU (theta=0) at
    a given east/north offset from the shared origin — a straight 25m walk."""
    import math
    from geo_registration import GeoRef
    events = []
    for i in range(n):
        east = offset_e + i * (25.0 / (n - 1))
        north = offset_n + i * (8.0 / (n - 1))
        events.append(GeoRef(
            at_ms=1e12 + i * 1000,
            lat=lat0 + north / 111_320.0,
            lng=lng0 + east / (111_320.0 * math.cos(math.radians(lat0))),
            accuracy_m=3.0,
            ar_xyz=np.array([east - offset_e, 1.5, -(north - offset_n)]),
            ar_t=100.0 + i,
        ))
    return events


def test_world_registered_sessions_place_by_solved_transform():
    a = _session("world-a", seed=1)
    a.geo_ref_events = _geo_events(offset_e=2.0, offset_n=3.0)
    b = _session("world-b", seed=1)  # same scene, so ICP overlap exists
    b.geo_ref_events = _geo_events(offset_e=2.5, offset_n=3.5)

    result = fuse_sessions([a, b])
    assert result.world_eligible is True
    assert result.north_aligned is True
    assert set(result.accepted_session_ids) == {"world-a", "world-b"}
    # The shared ENU origin is session a's FIRST event fix, so a's AR origin
    # lands at ~(0, 0); b started 0.5m east / 0.5m north of it (ICP may snap
    # the identical synthetic clouds together, hence the loose tolerance).
    origin_a = result.session_transforms["world-a"] @ np.array([0, 0, 0, 1.0])
    assert abs(origin_a[0]) < 0.5 and abs(origin_a[2]) < 0.5
    origin_b = result.session_transforms["world-b"] @ np.array([0, 0, 0, 1.0])
    assert np.hypot(origin_b[0] - 0.5, origin_b[2] - (-0.5)) < 1.2
    assert result.session_registrations["world-a"]["status"] == "world"


def test_local_only_session_excluded_from_world_merge():
    a = _session("world-a", seed=1)
    a.geo_ref_events = _geo_events()
    legacy = _session("legacy-b", seed=2)  # no geoRefEvents at all

    result = fuse_sessions([a, legacy])
    assert result.world_eligible is True
    assert result.accepted_session_ids == ["world-a"]
    assert "legacy-b" in result.rejected_session_ids


def test_all_legacy_sessions_take_legacy_path_and_stay_world_ineligible():
    result = fuse_sessions([_session("s1", seed=1), _session("s2", seed=1)])
    assert result.world_eligible is False
    assert result.session_registrations == {}
    assert "s1" in result.accepted_session_ids


# --- Manifest/index reconciliation (2026-07-28 bm4m21 regression) -------------

def test_fuse_cell_recovers_chunks_the_manifest_omitted(monkeypatch):
    """The bm4m21 shape: the phone wrote its manifest mid-session so it lists
    ONE key (a lone pose sidecar), while every capture artifact reached R2 and
    was indexed. Manifest-driven fusion saw no depth and produced nothing;
    reconciling against capture_chunks must recover the whole session."""
    import fuse_cell as fuse_cell_module
    from fuse_cell import fuse_cell

    fake_r2 = _identity_frame_fixture()
    truncated = r2_store.ManifestObject(
        key="raw/1:2/deviceA/sess-a-manifest.json",
        manifest={
            "sessionId": "sess-a",
            "deviceId": "deviceA",
            "path": [{"lat": 41.85, "lng": -87.65, "atMs": 1000}],
            # only the sidecar made it into the manifest
            "captureChunks": ["raw/1:2/sess-a/capture/chunk-000.jsonl"],
        },
    )
    monkeypatch.setattr(r2_store, "list_manifests_for_cell", lambda cell: [truncated])
    monkeypatch.setattr(r2_store, "download_bytes", lambda key: fake_r2[key])
    monkeypatch.setattr(r2_store, "next_run_version_for_cell", lambda cell: 1)
    monkeypatch.setattr(r2_store, "upload_bytes", lambda key, data, content_type: None)
    # the index saw everything
    monkeypatch.setattr(
        fuse_cell_module.chunk_index, "chunk_keys_for_cell",
        lambda cell, dsn=None: {"sess-a": sorted(fake_r2.keys())},
    )

    result = fuse_cell("1:2", api_base_url=None, train_splat=False)

    assert result.accepted_session_ids == ["sess-a"]
    assert truncated.manifest["captureChunks"] == sorted(fake_r2.keys())


def test_fuse_cell_without_index_keeps_manifest_only_behaviour(monkeypatch):
    """Fail-soft: an unreachable index must leave fusion exactly as it was,
    not break it. Same truncated manifest, empty index -> nothing usable."""
    import fuse_cell as fuse_cell_module
    from fuse_cell import fuse_cell

    fake_r2 = _identity_frame_fixture()
    truncated = r2_store.ManifestObject(
        key="raw/1:2/deviceA/sess-a-manifest.json",
        manifest={
            "sessionId": "sess-a",
            "deviceId": "deviceA",
            "path": [{"lat": 41.85, "lng": -87.65, "atMs": 1000}],
            "captureChunks": ["raw/1:2/sess-a/capture/chunk-000.jsonl"],
        },
    )
    monkeypatch.setattr(r2_store, "list_manifests_for_cell", lambda cell: [truncated])
    monkeypatch.setattr(r2_store, "download_bytes", lambda key: fake_r2[key])
    monkeypatch.setattr(
        fuse_cell_module.chunk_index, "chunk_keys_for_cell", lambda cell, dsn=None: {}
    )

    result = fuse_cell("1:2", api_base_url=None, train_splat=False)

    assert result.accepted_session_ids == []
    assert truncated.manifest["captureChunks"] == ["raw/1:2/sess-a/capture/chunk-000.jsonl"]
