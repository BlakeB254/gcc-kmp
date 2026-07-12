"""Privacy scrub core: pixelate+blur detected regions in a capture-chunk
video, preserving frame timing exactly (chunk_io.extract_video_frames_at
rebases pts by the stream's first pts — scrubbed renditions must carry the
same pts/time_base so depth↔video matching is unchanged for reconstruction).

Detector-agnostic: detection is an injected callable so this module needs
no GPU/network and is fully unit-testable. Two passes over the (in-memory)
input keep peak memory at one frame + boxes instead of the whole clip.
"""
from __future__ import annotations

import io
from dataclasses import dataclass, field
from typing import Callable

import av
import cv2
import numpy as np

Box = tuple[int, int, int, int]  # x1, y1, x2, y2 pixel coords


@dataclass(frozen=True)
class Detection:
    box: Box
    label: str
    conf: float


@dataclass
class ScrubOutput:
    video_bytes: bytes
    frame_count: int
    frames_with_detections: int
    detections_by_label: dict[str, int] = field(default_factory=dict)


def pad_box(box: Box, frac: float, w: int, h: int) -> Box:
    x1, y1, x2, y2 = box
    dx, dy = int((x2 - x1) * frac), int((y2 - y1) * frac)
    return (max(0, x1 - dx), max(0, y1 - dy), min(w, x2 + dx), min(h, y2 + dy))


def dilate_detections(per_frame: list[list[Detection]], radius: int) -> list[list[Detection]]:
    """Frame i's effective detections = union of raw detections on frames
    i-radius..i+radius. Bridges detector flicker; privacy errs to over-blur."""
    n = len(per_frame)
    out: list[list[Detection]] = []
    for i in range(n):
        merged: list[Detection] = []
        for j in range(max(0, i - radius), min(n, i + radius + 1)):
            merged.extend(per_frame[j])
        out.append(merged)
    return out


def pixelate_regions(frame_bgr: np.ndarray, boxes: list[Box], cell_px: int = 14) -> np.ndarray:
    h, w = frame_bgr.shape[:2]
    for x1, y1, x2, y2 in boxes:
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(w, x2), min(h, y2)
        if x2 - x1 < 2 or y2 - y1 < 2:
            continue
        roi = frame_bgr[y1:y2, x1:x2]
        small = cv2.resize(
            roi,
            (max(1, (x2 - x1) // cell_px), max(1, (y2 - y1) // cell_px)),
            # INTER_AREA box-averages every source pixel into its mosaic cell;
            # INTER_LINEAR merely samples and lets high-frequency detail leak
            # through the "blur".
            interpolation=cv2.INTER_AREA,
        )
        mosaic = cv2.resize(small, (x2 - x1, y2 - y1), interpolation=cv2.INTER_NEAREST)
        frame_bgr[y1:y2, x1:x2] = cv2.GaussianBlur(mosaic, (0, 0), sigmaX=3)
    return frame_bgr


def scrub_video_bytes(
    video_bytes: bytes,
    detect_frames: Callable[[list[np.ndarray]], list[list[Detection]]],
    *,
    dilation_radius: int = 6,
    pad_frac: float = 0.08,
    detect_batch: int = 16,
) -> ScrubOutput:
    # Pass 1: decode + detect (batched), keep only boxes.
    per_frame: list[list[Detection]] = []
    with av.open(io.BytesIO(video_bytes)) as container:
        batch: list[np.ndarray] = []
        for frame in container.decode(video=0):
            batch.append(frame.to_ndarray(format="bgr24"))
            if len(batch) == detect_batch:
                per_frame.extend(detect_frames(batch))
                batch = []
        if batch:
            per_frame.extend(detect_frames(batch))

    effective = dilate_detections(per_frame, dilation_radius)
    by_label: dict[str, int] = {}
    for dets in per_frame:
        for d in dets:
            by_label[d.label] = by_label.get(d.label, 0) + 1

    # Pass 2: decode again, blur, re-encode preserving pts/time_base. B-frames
    # would let the encoder reorder pts — disable them so pass-through pts
    # stay monotonic and the timing contract holds.
    out_buf = io.BytesIO()
    with av.open(io.BytesIO(video_bytes)) as src, av.open(out_buf, "w", format="mov") as dst:
        in_stream = src.streams.video[0]
        out_stream = dst.add_stream("libx264")
        out_stream.width = in_stream.codec_context.width
        out_stream.height = in_stream.codec_context.height
        out_stream.pix_fmt = "yuv420p"
        out_stream.codec_context.time_base = in_stream.time_base
        out_stream.options = {"crf": "23", "preset": "medium", "bf": "0"}
        w, h = out_stream.width, out_stream.height
        for i, frame in enumerate(src.decode(video=0)):
            img = frame.to_ndarray(format="bgr24")
            dets = effective[i] if i < len(effective) else []
            if dets:
                boxes = [pad_box(d.box, pad_frac, w, h) for d in dets]
                img = pixelate_regions(img, boxes)
            out = av.VideoFrame.from_ndarray(img, format="bgr24")
            out.pts = frame.pts
            out.time_base = frame.time_base
            dst.mux(out_stream.encode(out))
        dst.mux(out_stream.encode())

    return ScrubOutput(
        video_bytes=out_buf.getvalue(),
        frame_count=len(per_frame),
        frames_with_detections=sum(1 for d in per_frame if d),
        detections_by_label=by_label,
    )
