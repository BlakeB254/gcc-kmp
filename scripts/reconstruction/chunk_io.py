"""Decodes the REAL capture-chunk format produced by
iosApp/iosApp/ArSessionRecorder.swift (Pillar 1 session capture, merged
2026-07-07): per rotating chunk, three files --

  chunk-NNN.mov    HEVC (H.264 fallback) video of the color feed
  chunk-NNN.jsonl  one header line + one line per captured frame:
                   {"t":..,"transform":[16 floats],"intrinsics":[9 floats],
                    "tracking":0|1|2,"thermal":<raw>}
                   transform/intrinsics are COLUMN-MAJOR (simd_float4x4/
                   simd_float3x3's .columns.N flattened in order) -- NOT
                   row-major. Reshape with order='F', not a plain .reshape.
  chunk-NNN.depth  LiDAR depth keyframes at 2Hz, length-prefixed binary
                   records: [UInt32 recordLen (excl. itself)][Float64 t]
                   [UInt32 width][UInt32 height][UInt32 bytesPerRow]
                   [UInt32 pixelFormat][UInt32 compressedLen][LZFSE bytes],
                   all native little-endian. The LZFSE payload is the RAW
                   Float32 depth-in-meters buffer (height*bytesPerRow
                   bytes) before compression -- ARKit's depthMap is already
                   real-world meters, no mm/PNG scale-factor convention
                   needed (that convention only existed for this pipeline's
                   earlier, since-retired placeholder capture path).

This module supersedes this pipeline's original CapturedFrame/manifest-JSON
design (deleted along with the Kotlin scaffolding that produced it, which
was never actually wired into any real capture code -- ArSessionRecorder
replaced it before it shipped).
"""
from __future__ import annotations

import io
import json
import struct
from dataclasses import dataclass

import av
import liblzfse
import numpy as np

from frame_io import Intrinsics


@dataclass
class SidecarFrame:
    t: float
    camera_transform: np.ndarray  # 4x4, world = camera_transform @ point_camera_homogeneous
    intrinsics: Intrinsics | None  # width/height filled in by caller (sidecar only has fx/fy/cx/cy)
    tracking: int  # 0=normal, 1=limited, 2=other
    thermal: int


@dataclass
class DepthRecord:
    t: float
    depth_meters: np.ndarray  # (height, width) float32, row padding already stripped


def _matrix_from_column_major(values: list[float], n: int) -> np.ndarray:
    """simd's .columns.0/.columns.1/... flattened in order IS column-major
    storage -- reshape(n, n, order='F') reads it correctly. A plain
    .reshape(n, n) would silently transpose the matrix."""
    return np.array(values, dtype=np.float64).reshape(n, n, order="F")


def parse_sidecar(jsonl_bytes: bytes) -> tuple[dict, list[SidecarFrame]]:
    """First line is the chunk header (schema/sessionId/chunk/sourceWidth/
    sourceHeight/videoWidth/videoHeight); every line after is one captured
    frame.

    Per-frame `intrinsics` are ARKit's `camera.intrinsics`, calibrated
    against the SOURCE capture resolution (sourceWidth/sourceHeight) -- but
    this pipeline extracts color from the recorded VIDEO, which
    ArSessionRecorder.outputDimensions scales down to a smaller size
    (`videoWidth`/`videoHeight`). Returned Intrinsics are rescaled to video
    pixel space (and width/height set to the video's), so they're directly
    usable against whatever build_point_cloud_from_frame extracts from the
    .mov -- using the raw source-resolution intrinsics against a downscaled
    video frame would silently mis-project every point.
    """
    lines = jsonl_bytes.decode("utf-8").strip().split("\n")
    if not lines:
        raise ValueError("empty sidecar")
    header = json.loads(lines[0])
    source_width, source_height = header["sourceWidth"], header["sourceHeight"]
    video_width, video_height = header["videoWidth"], header["videoHeight"]
    scale_x = video_width / source_width
    scale_y = video_height / source_height

    frames = []
    for line in lines[1:]:
        if not line.strip():
            continue
        record = json.loads(line)
        transform = _matrix_from_column_major(record["transform"], 4)
        k = _matrix_from_column_major(record["intrinsics"], 3)
        # ARKit's camera.intrinsics, per Apple's docs, is
        # [[fx,0,0],[0,fy,0],[cx,cy,1]] -- reshaping the column-major-stored
        # flat array with order='F' reconstructs exactly that layout, so cx/cy
        # live at [2,0]/[2,1], NOT [0,2]/[1,2] (caught by
        # tests/test_chunk_io.py -- an earlier version of this line had them
        # transposed).
        intr = Intrinsics(
            fx=k[0, 0] * scale_x, fy=k[1, 1] * scale_y,
            cx=k[2, 0] * scale_x, cy=k[2, 1] * scale_y,
            width=video_width, height=video_height,
        )
        frames.append(SidecarFrame(
            t=record["t"],
            camera_transform=transform,
            intrinsics=intr,
            tracking=record["tracking"],
            thermal=record["thermal"],
        ))
    return header, frames


_DEPTH_RECORD_HEADER = struct.Struct("<dIIII")  # t, width, height, bytesPerRow, pixelFormat -- native little-endian, matches Swift `put()`'s host byte order on arm64/x86_64
_DEPTH_RECORD_LEN_PREFIX = struct.Struct("<I")


def parse_depth_chunk(depth_bytes: bytes) -> list[DepthRecord]:
    records = []
    offset = 0
    total = len(depth_bytes)
    while offset < total:
        (record_len,) = _DEPTH_RECORD_LEN_PREFIX.unpack_from(depth_bytes, offset)
        offset += _DEPTH_RECORD_LEN_PREFIX.size
        record_end = offset + record_len
        if record_end > total:
            raise ValueError(f"truncated depth record at offset {offset}: need {record_len} bytes, have {total - offset}")

        t, width, height, bytes_per_row, _pixel_format = _DEPTH_RECORD_HEADER.unpack_from(depth_bytes, offset)
        header_size = _DEPTH_RECORD_HEADER.size
        (compressed_len,) = struct.unpack_from("<I", depth_bytes, offset + header_size)
        compressed_start = offset + header_size + 4
        compressed = depth_bytes[compressed_start:compressed_start + compressed_len]

        raw = liblzfse.decompress(compressed)
        expected_size = height * bytes_per_row
        if len(raw) != expected_size:
            raise ValueError(f"decompressed depth size {len(raw)} != expected {expected_size} (height*bytesPerRow)")

        floats_per_row = bytes_per_row // 4
        as_float = np.frombuffer(raw, dtype="<f4").reshape(height, floats_per_row)
        depth_meters = np.ascontiguousarray(as_float[:, :width])

        records.append(DepthRecord(t=t, depth_meters=depth_meters))
        offset = record_end
    return records


def extract_video_frame_at(video_bytes: bytes, target_time: float) -> np.ndarray | None:
    """Decodes the .mov and returns the frame whose presentation timestamp
    is closest to target_time, as an RGB uint8 HxWx3 array. None if the
    container has no video stream / no frames (a chunk recorded entirely
    under thermal `.serious` pressure has no video at all -- see
    ArSessionRecorder's sidecar-only note)."""
    container = av.open(io.BytesIO(video_bytes))
    try:
        stream = next((s for s in container.streams if s.type == "video"), None)
        if stream is None:
            return None

        best_frame = None
        best_diff = float("inf")
        for frame in container.decode(stream):
            pts_seconds = float(frame.pts * stream.time_base) if frame.pts is not None else None
            if pts_seconds is None:
                continue
            diff = abs(pts_seconds - target_time)
            if diff < best_diff:
                best_diff = diff
                best_frame = frame.to_ndarray(format="rgb24")
        return best_frame
    finally:
        container.close()
