import io
import json
import struct
from fractions import Fraction

import av
import liblzfse
import numpy as np

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


def test_fuse_cell_without_api_base_url_never_calls_http(monkeypatch):
    import fuse_cell as fuse_cell_module

    _mock_cell_with_one_session(monkeypatch, run_version=1)

    def _boom(*args, **kwargs):
        raise AssertionError("HTTP provenance must not be called when api_base_url is None")

    monkeypatch.setattr(fuse_cell_module.requests, "post", _boom)
    result = fuse_cell_module.fuse_cell("1:2", api_base_url=None, train_splat=False)
    assert result.assets  # it still published to R2
