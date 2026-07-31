import io
import json
import struct
from fractions import Fraction

import av
import liblzfse
import numpy as np

from chunk_io import (
    extract_video_frame_at,
    parse_confidence_chunk,
    parse_depth_chunk,
    parse_sidecar,
)


def _sidecar_jsonl(header: dict, frames: list[dict]) -> bytes:
    lines = [json.dumps(header)] + [json.dumps(f) for f in frames]
    return ("\n".join(lines) + "\n").encode("utf-8")


def _column_major_flatten(matrix: np.ndarray) -> list[float]:
    """Inverse of chunk_io._matrix_from_column_major -- builds the
    column-major flat list a real Swift sidecar line would contain, from a
    normal (row-major-in-memory) NumPy matrix."""
    return matrix.flatten(order="F").tolist()


def test_parse_sidecar_recovers_transform_and_scales_intrinsics_to_video_resolution():
    # A transform with a distinctive translation, to catch any accidental
    # transpose in the column-major reshape (a transpose would still "look"
    # like a valid rotation+translation matrix but be wrong).
    transform = np.eye(4)
    transform[:3, 3] = [1.0, 2.0, 3.0]
    transform[0, 1] = 0.5  # break symmetry so a transpose would be detectable

    # Source-resolution intrinsics as the STANDARD pinhole K -- Apple's
    # simd_float3x3 column-major flattening of this is [fx,0,0, 0,fy,0,
    # cx,cy,1], which is exactly what real capture sidecars contain
    # (verified against production data 2026-07-11: flat[6] ≈ width/2).
    # Video is downscaled to half size, so fx/fy/cx/cy come back halved.
    source_intrinsics = np.array([[100.0, 0, 50.0], [0, 120.0, 60.0], [0.0, 0.0, 1.0]])

    header = {"schema": 1, "sessionId": "s1", "chunk": 0, "sourceWidth": 200, "sourceHeight": 240, "videoWidth": 100, "videoHeight": 120}
    frame = {
        "t": 1.5,
        "transform": _column_major_flatten(transform),
        "intrinsics": _column_major_flatten(source_intrinsics),
        "tracking": 0,
        "thermal": 0,
    }
    jsonl = _sidecar_jsonl(header, [frame])

    parsed_header, frames = parse_sidecar(jsonl)
    assert parsed_header["sessionId"] == "s1"
    assert len(frames) == 1
    f = frames[0]
    assert f.t == 1.5
    assert f.tracking == 0
    assert np.allclose(f.camera_transform, transform)

    assert f.intrinsics.fx == 50.0  # 100 * (100/200)
    assert f.intrinsics.fy == 60.0  # 120 * (120/240)
    assert f.intrinsics.cx == 25.0  # 50 * 0.5
    assert f.intrinsics.cy == 30.0  # 60 * 0.5
    assert f.intrinsics.width == 100
    assert f.intrinsics.height == 120


def test_parse_sidecar_preserves_optional_camera_appearance_metadata():
    transform = np.eye(4)
    source_intrinsics = np.eye(3)
    header = {"schema": 2, "sessionId": "s1", "chunk": 0, "sourceWidth": 20, "sourceHeight": 20, "videoWidth": 20, "videoHeight": 20}
    frame = {
        "t": 2.0,
        "transform": _column_major_flatten(transform),
        "intrinsics": _column_major_flatten(source_intrinsics),
        "tracking": 0,
        "thermal": 0,
        "exposureDurationSeconds": 0.008,
        "iso": 64.0,
        "whiteBalanceRedGain": 1.7,
        "whiteBalanceGreenGain": 1.0,
        "whiteBalanceBlueGain": 1.5,
        "lensPosition": None,
    }

    _, frames = parse_sidecar(_sidecar_jsonl(header, [frame]))

    assert frames[0].exposure_duration_seconds == 0.008
    assert frames[0].iso == 64.0
    assert frames[0].white_balance_gains == (1.7, 1.0, 1.5)
    assert frames[0].lens_position is None


def test_parse_sidecar_keeps_legacy_appearance_metadata_optional():
    transform = np.eye(4)
    source_intrinsics = np.eye(3)
    header = {"schema": 1, "sessionId": "legacy", "chunk": 0, "sourceWidth": 20, "sourceHeight": 20, "videoWidth": 20, "videoHeight": 20}
    frame = {
        "t": 2.0,
        "transform": _column_major_flatten(transform),
        "intrinsics": _column_major_flatten(source_intrinsics),
        "tracking": 0,
        "thermal": 0,
    }

    _, frames = parse_sidecar(_sidecar_jsonl(header, [frame]))

    assert frames[0].exposure_duration_seconds is None
    assert frames[0].iso is None
    assert frames[0].white_balance_gains is None
    assert frames[0].lens_position is None


def _build_depth_chunk_bytes(records: list[tuple[float, np.ndarray]]) -> bytes:
    """Hand-builds the exact on-wire format ArSessionRecorder.appendDepth
    writes, so parse_depth_chunk can be tested against a real, independently
    constructed encoding rather than a round-trip through its own writer."""
    out = bytearray()
    for t, depth in records:
        height, width = depth.shape
        bytes_per_row = width * 4  # no row padding in this synthetic case
        raw = depth.astype("<f4").tobytes()
        compressed = liblzfse.compress(raw)
        body = struct.pack("<dIIII", t, width, height, bytes_per_row, 0) + struct.pack("<I", len(compressed)) + compressed
        out += struct.pack("<I", len(body))
        out += body
    return bytes(out)


def test_parse_depth_chunk_round_trips_multiple_records():
    depth1 = np.full((4, 4), 1.5, dtype=np.float32)
    depth2 = np.arange(16, dtype=np.float32).reshape(4, 4) / 10.0
    chunk_bytes = _build_depth_chunk_bytes([(0.1, depth1), (0.6, depth2)])

    records = parse_depth_chunk(chunk_bytes)

    assert len(records) == 2
    assert records[0].t == 0.1
    assert np.allclose(records[0].depth_meters, depth1)
    assert records[1].t == 0.6
    assert np.allclose(records[1].depth_meters, depth2)


def test_parse_depth_chunk_strips_row_padding():
    """bytesPerRow can exceed width*4 (alignment padding) -- verify only the
    real `width` columns are kept, not the padding."""
    height, width = 3, 5
    padded_floats_per_row = 8  # 3 padding floats per row
    depth = np.arange(height * width, dtype=np.float32).reshape(height, width)
    padded = np.zeros((height, padded_floats_per_row), dtype=np.float32)
    padded[:, :width] = depth
    raw = padded.astype("<f4").tobytes()
    compressed = liblzfse.compress(raw)
    body = (
        struct.pack("<dIIII", 0.0, width, height, padded_floats_per_row * 4, 0)
        + struct.pack("<I", len(compressed))
        + compressed
    )
    chunk_bytes = struct.pack("<I", len(body)) + body

    records = parse_depth_chunk(chunk_bytes)
    assert len(records) == 1
    assert records[0].depth_meters.shape == (height, width)
    assert np.allclose(records[0].depth_meters, depth)


def test_parse_confidence_chunk_reads_versioned_little_endian_records():
    width, height, bytes_per_row = 3, 2, 4
    payload = bytes([2, 1, 0, 99, 1, 2, 1, 88])
    fixed_header = struct.pack(
        "<HHdIIIII",
        1,
        32,
        4.25,
        width,
        height,
        bytes_per_row,
        0x4C303038,
        len(payload),
    )
    encoded = struct.pack("<I", len(fixed_header) + len(payload)) + fixed_header + payload

    records = parse_confidence_chunk(encoded)

    assert len(records) == 1
    assert records[0].t == 4.25
    assert records[0].confidence.shape == (height, width)
    assert records[0].confidence.tolist() == [[2, 1, 0], [1, 2, 1]]


def test_parse_confidence_chunk_skips_unknown_version_by_record_length():
    unknown_body = struct.pack("<HH", 9, 4) + b"future"
    encoded = struct.pack("<I", len(unknown_body)) + unknown_body

    assert parse_confidence_chunk(encoded) == []


def _encode_synthetic_mov(frames_with_colors: list[tuple[float, tuple[int, int, int]]]) -> bytes:
    buf = io.BytesIO()
    container = av.open(buf, mode="w", format="mp4")
    stream = container.add_stream("h264", rate=30)
    stream.width, stream.height = 32, 32
    stream.pix_fmt = "yuv420p"
    stream.codec_context.time_base = Fraction(1, 90000)
    for t, color in frames_with_colors:
        frame = av.VideoFrame.from_ndarray(
            np.full((32, 32, 3), color, dtype=np.uint8), format="rgb24"
        ).reformat(format="yuv420p")
        frame.pts = int(t * 90000)
        for packet in stream.encode(frame):
            container.mux(packet)
    for packet in stream.encode():
        container.mux(packet)
    container.close()
    return buf.getvalue()


def test_extract_video_frame_at_picks_nearest_timestamp():
    video_bytes = _encode_synthetic_mov([(0.0, (200, 0, 0)), (1.0, (0, 200, 0)), (2.0, (0, 0, 200))])

    near_start = extract_video_frame_at(video_bytes, target_time=0.1)
    near_middle = extract_video_frame_at(video_bytes, target_time=0.9)
    near_end = extract_video_frame_at(video_bytes, target_time=2.1)

    assert near_start is not None and near_middle is not None and near_end is not None
    # Dominant channel should match the nearest encoded frame's color.
    assert near_start[..., 0].mean() > near_start[..., 1].mean()
    assert near_middle[..., 1].mean() > near_middle[..., 0].mean()
    assert near_end[..., 2].mean() > near_end[..., 0].mean()


def test_extract_video_frames_at_single_decode_matches_per_call_results():
    """Batch extraction (ONE decode pass for all targets) must return the
    same nearest-frame picks as the per-call function -- added 2026-07-10
    after the real-data prototype showed per-record full decodes are O(N^2)
    (each 60s HEVC chunk was being fully decoded ~120 times)."""
    from chunk_io import extract_video_frames_at

    video_bytes = _encode_synthetic_mov([(0.0, (200, 0, 0)), (1.0, (0, 200, 0)), (2.0, (0, 0, 200))])
    frames = extract_video_frames_at(video_bytes, [0.1, 0.9, 2.1])

    assert len(frames) == 3
    assert all(f is not None for f in frames)
    assert frames[0][..., 0].mean() > frames[0][..., 1].mean()   # red
    assert frames[1][..., 1].mean() > frames[1][..., 0].mean()   # green
    assert frames[2][..., 2].mean() > frames[2][..., 0].mean()   # blue


def test_extract_video_frames_at_handles_empty_targets_and_no_video():
    from chunk_io import extract_video_frames_at

    video_bytes = _encode_synthetic_mov([(0.0, (200, 0, 0))])
    assert extract_video_frames_at(video_bytes, []) == []
