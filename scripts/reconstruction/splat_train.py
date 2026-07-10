"""Produces a fused Gaussian-splat asset for one locationCellId, once
fuse_cell.py has already registered its sessions (accepted, with per-session
alignment transforms).

Per-session (not pooled) reconstruction via cdx-world-mirror, per
gaussian_ply.py's module docstring: world-mirror does one fresh joint solve
per call with no cross-session registration of its own, so it's only
trustworthy over one session's own frames (already internally consistent --
ARKit maintains one coherent map for the whole walk). This module supplies
the cross-session alignment itself, reusing the exact transform
fuse_sessions() already computed for that session via GPS-seeded ICP.
"""
from __future__ import annotations

import io
import os
from dataclasses import dataclass

import numpy as np
from PIL import Image

import chunk_io
import r2_store
import world_mirror_client
from fuse_cell import FusionResult, _group_chunks_by_index
from gaussian_ply import concatenate_gaussian_ply_bytes, transform_gaussian_ply_bytes

MIN_FRAMES_PER_SESSION = 2
MAX_FRAMES_PER_SESSION = 16


@dataclass
class SessionSplatResult:
    session_id: str
    splat_bytes: bytes | None  # None when this session didn't have enough frames
    skip_reason: str | None = None


def gather_session_frames(manifest: dict, max_frames: int = MAX_FRAMES_PER_SESSION) -> list[bytes]:
    """Evenly-spaced JPEG frames sampled from every chunk's video, using the
    chunk's own sidecar timestamps (not guessed video-duration math) --
    real captured-frame times are already known exactly."""
    chunk_keys = manifest.get("captureChunks") or []
    chunks = _group_chunks_by_index(chunk_keys)
    chunks_with_video = {i: kinds for i, kinds in chunks.items() if "mov" in kinds and "jsonl" in kinds}
    if not chunks_with_video:
        return []

    per_chunk_budget = max(1, max_frames // len(chunks_with_video))
    frames: list[bytes] = []
    for kinds in chunks_with_video.values():
        video_bytes = r2_store.download_bytes(kinds["mov"])
        sidecar_bytes = r2_store.download_bytes(kinds["jsonl"])
        _header, sidecar_frames = chunk_io.parse_sidecar(sidecar_bytes)
        if not sidecar_frames:
            continue

        sample_count = min(per_chunk_budget, len(sidecar_frames))
        sample_indices = np.linspace(0, len(sidecar_frames) - 1, sample_count, dtype=int)
        for idx in sample_indices:
            t = sidecar_frames[idx].t
            color_img = chunk_io.extract_video_frame_at(video_bytes, t)
            if color_img is None:
                continue
            buf = io.BytesIO()
            Image.fromarray(color_img).save(buf, format="JPEG", quality=90)
            frames.append(buf.getvalue())
        if len(frames) >= max_frames:
            break
    return frames[:max_frames]


def train_splat_for_session(session_manifest: dict, world_mirror_base_url: str) -> SessionSplatResult:
    session_id = session_manifest.get("sessionId", "unknown")
    frames = gather_session_frames(session_manifest)
    if len(frames) < MIN_FRAMES_PER_SESSION:
        return SessionSplatResult(session_id=session_id, splat_bytes=None, skip_reason=f"only {len(frames)} usable frames (need {MIN_FRAMES_PER_SESSION}+)")

    try:
        splat_bytes = world_mirror_client.reconstruct_session(frames, base_url=world_mirror_base_url)
    except world_mirror_client.WorldMirrorError as e:
        return SessionSplatResult(session_id=session_id, splat_bytes=None, skip_reason=str(e))
    return SessionSplatResult(session_id=session_id, splat_bytes=splat_bytes)


def train_splat_for_cell(
    cell_id: str,
    fusion_result: FusionResult,
    session_manifests_by_id: dict[str, dict],
    world_mirror_base_url: str = world_mirror_client.DEFAULT_BASE_URL,
) -> bytes | None:
    """For every ACCEPTED session (fusion_result.accepted_session_ids), get
    its own world-mirror splat, transform it into the shared cell frame with
    the exact transform fuse_sessions() already computed, and merge. None if
    no session produced a usable splat (e.g. world-mirror unreachable, or no
    session had enough frames -- not fatal to the point-cloud output
    fuse_cell.py already writes regardless)."""
    transformed_splats = []
    for session_id in fusion_result.accepted_session_ids:
        manifest = session_manifests_by_id.get(session_id)
        if manifest is None:
            continue
        result = train_splat_for_session(manifest, world_mirror_base_url)
        if result.splat_bytes is None:
            print(f"splat_train: session {session_id} skipped -- {result.skip_reason}")
            continue
        transform = fusion_result.session_transforms[session_id]
        transformed_splats.append(transform_gaussian_ply_bytes(result.splat_bytes, transform))

    if not transformed_splats:
        return None
    if len(transformed_splats) == 1:
        return transformed_splats[0]
    return concatenate_gaussian_ply_bytes(transformed_splats)
