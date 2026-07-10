"""Orchestrates the full fusion pipeline for one locationCellId: pull every
session's raw frames from R2, register each session against the accumulating
cell point cloud (GPS-seeded ICP), enforce the activation-count gate before
treating the result as canonical, write the merged cloud back to R2 as an
open-format .ply, and record provenance via the admin API.

Fuse-logic (fuse_sessions) is separated from R2 I/O (fuse_cell) specifically
so the actual fusion algorithm is testable with synthetic in-memory sessions,
without needing real R2 data -- which doesn't exist yet (see README).
"""
from __future__ import annotations

import argparse
import os
import re
from dataclasses import dataclass, field

import numpy as np
import open3d as o3d
import requests

import chunk_io
import frame_io
import r2_store
from registration import GpsAnchor, gps_seeded_initial_transform, register_session_into_cell

# Only ~2Hz depth keyframes actually get fused (video is much higher rate) --
# a depth record more than this far from its nearest sidecar pose sample, or
# from a video frame, isn't trustworthy enough to use (tracking may have
# drifted meaningfully in that gap).
MAX_POSE_TIME_GAP_SECONDS = 0.2
# Depth chunks upload with the .bin extension in practice (verified against
# real R2 keys 2026-07-10) even though ArSessionRecorder's doc-comment says
# .depth -- accept both, normalized to the "depth" kind in
# _group_chunks_by_index.
_CHUNK_KEY_PATTERN = re.compile(r"chunk-(\d+)\.(mov|jsonl|depth|bin)$")

# A cell isn't canonical until this many independent sessions have
# successfully registered -- design spec §3.1's activation gate. This exact
# number is explicitly an empirical/research-spike question the 2026-07-03
# doc leaves open (Niantic's own public materials don't disclose theirs
# either) -- 3 is a starting default, not a validated threshold.
MIN_SESSIONS_FOR_ACTIVATION = 3
MERGE_VOXEL_SIZE_METERS = 0.02


@dataclass
class SessionFusionInput:
    """One session's worth of built point-cloud data, ready to register.
    anchor_lat/anchor_lng/anchor_local_xyz describe the same reference frame
    (e.g. the session's first captured frame) used for GPS seeding."""
    session_id: str
    device_id: str
    points: np.ndarray
    colors: np.ndarray
    anchor_lat: float
    anchor_lng: float
    anchor_local_xyz: np.ndarray


@dataclass
class ProducedAsset:
    """One artifact this run published to R2. `data` is kept in-memory so a
    post-processing consumer (recon_worker's splat-web compression) can work
    on it without a re-download; None once a caller deliberately drops it."""
    asset_type: str  # 'pointcloud' | 'splat' | 'splat-web'
    fmt: str         # 'ply' | 'spz' | ...
    r2_key: str
    version: int
    data: bytes | None


@dataclass
class FusionResult:
    accepted_session_ids: list[str] = field(default_factory=list)
    rejected_session_ids: list[str] = field(default_factory=list)
    merged_points: np.ndarray = field(default_factory=lambda: np.zeros((0, 3)))
    merged_colors: np.ndarray = field(default_factory=lambda: np.zeros((0, 3)))
    activated: bool = False
    # Per-accepted-session 4x4 transform into the shared cell frame -- the
    # exact transform register_session_into_cell computed for that session.
    # splat_train.py reuses these to align each session's INDEPENDENTLY
    # world-mirror-reconstructed splat into the same frame, rather than
    # re-deriving alignment (or worse, feeding multiple sessions' raw frames
    # into one world-mirror call, which has no cross-session registration
    # of its own -- see gaussian_ply.py's module docstring).
    session_transforms: dict[str, np.ndarray] = field(default_factory=dict)
    # Every artifact fuse_cell published this run, all sharing one version.
    assets: list[ProducedAsset] = field(default_factory=list)


def fuse_sessions(sessions: list[SessionFusionInput]) -> FusionResult:
    """Pure fusion logic, no R2/network access -- register each session in
    order against whatever has accumulated so far, keep only accepted
    registrations, gate activation on the accepted count."""
    result = FusionResult()
    accumulated_points: np.ndarray | None = None
    accumulated_colors: np.ndarray | None = None
    accumulated_anchor: GpsAnchor | None = None

    for session in sessions:
        session_anchor = GpsAnchor(lat=session.anchor_lat, lng=session.anchor_lng, local_xyz=session.anchor_local_xyz)

        if accumulated_anchor is None:
            registration = register_session_into_cell(None, session.points, initial_transform=None)
        else:
            seed = gps_seeded_initial_transform(accumulated_anchor, session_anchor)
            registration = register_session_into_cell(accumulated_points, session.points, initial_transform=seed)

        if not registration.accepted:
            result.rejected_session_ids.append(session.session_id)
            continue

        transformed_points = frame_io.apply_transform(session.points, registration.transform)
        if accumulated_points is None:
            accumulated_points = transformed_points
            accumulated_colors = session.colors
            accumulated_anchor = session_anchor
        else:
            merged = frame_io.merge_session_point_clouds(
                [
                    frame_io.PointCloudArrays(points=accumulated_points, colors=accumulated_colors),
                    frame_io.PointCloudArrays(points=transformed_points, colors=session.colors),
                ],
                voxel_size=MERGE_VOXEL_SIZE_METERS,
            )
            accumulated_points, accumulated_colors = merged.points, merged.colors
        result.accepted_session_ids.append(session.session_id)
        result.session_transforms[session.session_id] = registration.transform

    result.merged_points = accumulated_points if accumulated_points is not None else np.zeros((0, 3))
    result.merged_colors = accumulated_colors if accumulated_colors is not None else np.zeros((0, 3))
    result.activated = len(result.accepted_session_ids) >= MIN_SESSIONS_FOR_ACTIVATION
    return result


def _group_chunks_by_index(capture_chunk_keys: list[str]) -> dict[int, dict[str, str]]:
    """{chunk_index: {"mov": key, "jsonl": key, "depth": key}} -- kind is
    inferred from the R2 key's extension (chunk-NNN.{mov,jsonl,depth,bin};
    .bin is what real uploads use for depth and is normalized to "depth"),
    matching ArSessionRecorder's file-per-kind convention. Missing kinds
    (e.g. no .mov when a whole chunk was recorded under thermal .serious
    pressure -- sidecar-only) are simply absent from the inner dict."""
    grouped: dict[int, dict[str, str]] = {}
    for key in capture_chunk_keys:
        match = _CHUNK_KEY_PATTERN.search(key)
        if not match:
            continue
        index, kind = int(match.group(1)), match.group(2)
        if kind == "bin":
            kind = "depth"
        grouped.setdefault(index, {})[kind] = key
    return grouped


def _fuse_one_chunk(video_bytes: bytes | None, sidecar_bytes: bytes, depth_bytes: bytes) -> list[frame_io.PointCloudArrays]:
    """One chunk's depth keyframes (~2Hz), each matched to its nearest
    sidecar pose sample and nearest extracted video frame by timestamp.
    Video is optional -- a chunk recorded entirely under thermal `.serious`
    pressure has sidecar+depth but no video, so nothing to color a point
    cloud with; skipped (empty list), not an error."""
    if video_bytes is None:
        return []

    _header, sidecar_frames = chunk_io.parse_sidecar(sidecar_bytes)
    if not sidecar_frames:
        return []
    depth_records = chunk_io.parse_depth_chunk(depth_bytes)

    sidecar_times = np.array([f.t for f in sidecar_frames])

    # Pose-match first, then extract ALL needed color frames in ONE decode
    # pass (extract_video_frames_at) -- per-record decodes were O(N^2) on
    # real 60s chunks (see that function's docstring).
    usable: list[tuple] = []  # (depth_record, pose_frame)
    for depth_record in depth_records:
        nearest_idx = int(np.argmin(np.abs(sidecar_times - depth_record.t)))
        pose_frame = sidecar_frames[nearest_idx]
        if abs(pose_frame.t - depth_record.t) > MAX_POSE_TIME_GAP_SECONDS:
            continue  # pose sample too far from this depth keyframe to trust
        usable.append((depth_record, pose_frame))
    if not usable:
        return []

    color_imgs = chunk_io.extract_video_frames_at(video_bytes, [d.t for d, _ in usable])

    clouds = []
    for (depth_record, pose_frame), color_img in zip(usable, color_imgs):
        if color_img is None:
            continue
        cloud = frame_io.build_point_cloud_from_frame(
            color_img, depth_record.depth_meters, pose_frame.camera_transform, pose_frame.intrinsics
        )
        if cloud.points.shape[0] > 0:
            clouds.append(cloud)
    return clouds


def _session_fusion_input_from_manifest(manifest_obj: r2_store.ManifestObject) -> SessionFusionInput | None:
    """Downloads this session's capture chunks (HEVC video + pose sidecar +
    LZFSE depth keyframes, per ArSessionRecorder.swift) from R2 and builds
    one fused, session-local point cloud. None if the manifest has no
    capture chunks at all (older manifests, or a session where capture never
    started/produced anything) -- nothing to register for this session yet.

    GPS anchor: the sidecar has no GPS (ARKit's frame.timestamp is a
    monotonic clock, not wall-clock epoch, so it can't be directly compared
    to the manifest path's atMs anyway) -- approximated here as the
    manifest's FIRST GPS path sample paired with the FIRST sidecar frame's
    camera_transform. Both are close to session start, so this is a
    reasonable stand-in for a GPS-seeded ICP initial guess (which only needs
    to be roughly right; ICP refines the rest) -- not validated against a
    real device's actual startup-lag characteristics.
    """
    manifest = manifest_obj.manifest
    chunk_keys = manifest.get("captureChunks") or []
    chunks = _group_chunks_by_index(chunk_keys)
    if not chunks:
        return None

    path = manifest.get("path") or []
    if not path:
        return None
    anchor_lat, anchor_lng = path[0]["lat"], path[0]["lng"]

    clouds: list[frame_io.PointCloudArrays] = []
    anchor_xyz = None
    for index in sorted(chunks):
        kinds = chunks[index]
        if "jsonl" not in kinds or "depth" not in kinds:
            continue  # no pose or no depth for this chunk -- nothing to fuse
        video_bytes = r2_store.download_bytes(kinds["mov"]) if "mov" in kinds else None
        sidecar_bytes = r2_store.download_bytes(kinds["jsonl"])
        depth_bytes = r2_store.download_bytes(kinds["depth"])

        if anchor_xyz is None:
            _header, sidecar_frames = chunk_io.parse_sidecar(sidecar_bytes)
            if sidecar_frames:
                anchor_xyz = sidecar_frames[0].camera_transform[:3, 3]

        clouds.extend(_fuse_one_chunk(video_bytes, sidecar_bytes, depth_bytes))

    if not clouds or anchor_xyz is None:
        return None

    merged = frame_io.merge_session_point_clouds(clouds, voxel_size=MERGE_VOXEL_SIZE_METERS)
    return SessionFusionInput(
        session_id=manifest.get("sessionId", manifest_obj.key),
        device_id=manifest.get("deviceId", "unknown"),
        points=merged.points,
        colors=merged.colors,
        anchor_lat=anchor_lat,
        anchor_lng=anchor_lng,
        anchor_local_xyz=anchor_xyz,
    )


def _write_ply_to_r2(cell_id: str, asset_type: str, points: np.ndarray, colors: np.ndarray, version: int) -> tuple[str, bytes]:
    """The one Open3D call this pipeline still uses -- .ply I/O, confirmed
    safe on this hardware (see README). Returns (r2_key, ply_bytes) so the
    caller can record the asset without a re-download.

    Vector3dVector on this box SEGFAULTS on anything but contiguous float64
    input (float32 or Fortran-ordered arrays crash -- probed dtype-by-dtype
    2026-07-10, same class of aarch64 breakage as the README's KDTree/ICP
    list), and the pipeline's arrays are float32 -- coerce first."""
    pcd = o3d.geometry.PointCloud()
    pcd.points = o3d.utility.Vector3dVector(np.ascontiguousarray(points, dtype=np.float64))
    pcd.colors = o3d.utility.Vector3dVector(np.ascontiguousarray(colors, dtype=np.float64))

    tmp_path = f"/tmp/fuse_{cell_id.replace(':', '_')}_{asset_type}.ply"
    o3d.io.write_point_cloud(tmp_path, pcd)
    with open(tmp_path, "rb") as f:
        data = f.read()
    os.remove(tmp_path)

    return _write_bytes_to_r2(cell_id, asset_type, "ply", data, version), data


def _write_bytes_to_r2(cell_id: str, asset_type: str, fmt: str, data: bytes, version: int) -> str:
    """Version is computed ONCE per run by the caller
    (r2_store.next_run_version_for_cell) so every asset type in one run
    shares one reconstructed/{cell}/{version}/ directory -- a re-run never
    silently overwrites a prior version's physical files."""
    key = r2_store.reconstructed_asset_key(cell_id, version=version, asset_type=asset_type, fmt=fmt)
    r2_store.upload_bytes(key, data, content_type="application/octet-stream")
    return key


def _record_provenance(cell_id: str, r2_key: str, asset_type: str, fmt: str, activated: bool, api_base_url: str) -> None:
    secret = os.environ.get("RECONSTRUCTION_PIPELINE_SECRET")
    if not secret:
        print("RECONSTRUCTION_PIPELINE_SECRET not set -- skipping provenance write, asset is still in R2")
        return
    resp = requests.post(
        f"{api_base_url}/api/admin/reconstruction-assets",
        headers={"Authorization": f"Bearer {secret}"},
        json={
            "locationCellId": cell_id,
            "assetType": asset_type,
            "format": fmt,
            "r2Key": r2_key,
            # Not yet resolved to real scanCoverageEvents rows -- this
            # pipeline only has session manifests, not event IDs, from R2
            # alone. A more complete version would look these up server-side
            # or have this pipeline query Postgres directly for the matching
            # events by (locationCellId, sessionId's device+time window).
            "sourceScanCoverageEventIds": [],
            "activate": activated,
        },
        timeout=30,
    )
    resp.raise_for_status()


def fuse_cell(
    cell_id: str,
    api_base_url: str | None = None,
    train_splat: bool = True,
    require_activation_for_splat: bool = True,
) -> FusionResult:
    """api_base_url=None (the default, and how recon_worker calls this)
    skips the HTTP provenance write entirely -- the worker records
    provenance directly in Postgres from FusionResult.assets instead."""
    manifests = r2_store.list_manifests_for_cell(cell_id)
    manifests_by_session_id = {m.manifest.get("sessionId", m.key): m.manifest for m in manifests}
    sessions = [s for m in manifests if (s := _session_fusion_input_from_manifest(m)) is not None]
    print(f"cell {cell_id}: {len(manifests)} manifests, {len(sessions)} with usable depth-backed frames")

    result = fuse_sessions(sessions)
    print(
        f"cell {cell_id}: {len(result.accepted_session_ids)} accepted, "
        f"{len(result.rejected_session_ids)} rejected, activated={result.activated}"
    )

    run_version: int | None = None

    def _run_version() -> int:
        nonlocal run_version
        if run_version is None:
            run_version = r2_store.next_run_version_for_cell(cell_id)
        return run_version

    if result.merged_points.shape[0] > 0:
        version = _run_version()
        r2_key, ply_bytes = _write_ply_to_r2(cell_id, "pointcloud", result.merged_points, result.merged_colors, version)
        result.assets.append(ProducedAsset("pointcloud", "ply", r2_key, version, ply_bytes))
        print(f"cell {cell_id}: wrote {r2_key}")
        if api_base_url:
            _record_provenance(cell_id, r2_key, "pointcloud", "ply", result.activated, api_base_url)

    # Splat production is real GPU-inference cost (cdx-world-mirror). The
    # default only spends it on a cell the activation gate has already
    # decided is canonical; require_activation_for_splat=False (the
    # prototype/worker setting while activation-count data is still scarce)
    # produces a splat for any successful fusion -- activated_at in the DB
    # still honestly records the gate's verdict either way.
    splat_wanted = train_splat and result.accepted_session_ids and (
        result.activated or not require_activation_for_splat
    )
    if splat_wanted:
        import splat_train  # deferred: avoids a hard dependency (plyfile,
        # a live world-mirror endpoint) for callers that only need the
        # point-cloud path, e.g. this module's own test suite.

        splat_bytes = splat_train.train_splat_for_cell(cell_id, result, manifests_by_session_id)
        if splat_bytes is not None:
            version = _run_version()
            splat_key = _write_bytes_to_r2(cell_id, "splat", "ply", splat_bytes, version)
            result.assets.append(ProducedAsset("splat", "ply", splat_key, version, splat_bytes))
            print(f"cell {cell_id}: wrote {splat_key}")
            if api_base_url:
                _record_provenance(cell_id, splat_key, "splat", "ply", activated=result.activated, api_base_url=api_base_url)
        else:
            print(f"cell {cell_id}: no session produced a usable splat -- point cloud is still written")

    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cell_id", help="locationCellId, e.g. '419500:-876234'")
    parser.add_argument(
        "--api-base-url",
        default=os.environ.get("GCC_API_BASE_URL"),
        help="Base URL for the admin reconstruction-assets HTTP provenance "
        "write; omit (default) to skip HTTP entirely -- recon_worker records "
        "provenance directly in Postgres instead",
    )
    parser.add_argument(
        "--no-splat", action="store_true",
        help="Skip cdx-world-mirror splat production, write only the point cloud",
    )
    parser.add_argument(
        "--splat-unactivated", action="store_true",
        help="Produce a splat even when the cell hasn't hit the "
        "activation-count gate (prototype mode)",
    )
    args = parser.parse_args()
    fuse_cell(
        args.cell_id,
        args.api_base_url,
        train_splat=not args.no_splat,
        require_activation_for_splat=not args.splat_unactivated,
    )
