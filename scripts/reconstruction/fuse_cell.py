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
import geo_registration
import r2_store
from registration import GpsAnchor, gps_seeded_initial_transform, register_session_into_cell
from splat_outcome import resolve_splat_outcome

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
    # For north alignment: the session's ARKit camera track [(t, xyz)] and
    # the manifest GPS path samples [{lat,lng,atMs}] + startedAtMs bridge.
    track: list = field(default_factory=list)
    gps_path: list = field(default_factory=list)
    started_at_ms: float = 0.0
    # manifest.geoRefEvents (PR #149) parsed into geo_registration.GeoRef --
    # empty for legacy sessions, which the contract keeps LOCAL-ONLY.
    geo_ref_events: list = field(default_factory=list)


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
    # Geo-referencing of the cell frame: origin ≈ the anchor session's ARKit
    # start, at this lat/lng, with axes north-aligned (+X=east, -Z=north)
    # when northAligned is True.
    anchor_lat: float | None = None
    anchor_lng: float | None = None
    north_aligned: bool = False
    north_rms_m: float | None = None
    # Session -> world-splat registration (scan-splat-registration-contract):
    # per-session solve records (WorldRegistration.to_json()) and whether the
    # merged outputs are eligible for the world layer (True only when every
    # merged session solved 'world' from its geoRefEvents; legacy GPS-track
    # north fits are honest for display but NEVER world-mergeable).
    session_registrations: dict = field(default_factory=dict)
    world_eligible: bool = False
    # Why no splat asset was produced this run -- None iff a splat asset IS
    # in `assets`. See splat_outcome.py's module docstring for the exact
    # vocabulary; recon_worker.py's complete_cell() writes this into
    # reconstruction_jobs.splat_skip_reason (migration 0039).
    splat_skip_reason: str | None = None


def fuse_sessions(sessions: list[SessionFusionInput]) -> FusionResult:
    """Pure fusion logic, no R2/network access. Two paths:

    WORLD PATH (scan-splat-registration-contract, PR #149): when any session
    solves a world registration from its geoRefEvents, every world-solved
    session is placed by its own solved transform -- all solved against ONE
    shared ENU origin, so they land mutually consistent, north-true and
    metric by construction -- then ICP-refined against the accumulation.
    Local-only sessions are rejected from the merge (the contract's hard
    rule: they never pollute the world layer).

    LEGACY PATH (no world-solved session): register each session in order
    against whatever has accumulated so far via GPS-seeded ICP, then fit a
    north yaw from the anchor's GPS track. Honest for display; never
    world-eligible."""
    result = FusionResult()

    # ── Attempt world registration for every session ──
    origin = next(((s.geo_ref_events[0].lat, s.geo_ref_events[0].lng)
                   for s in sessions if s.geo_ref_events), None)
    registrations = {}
    if origin is not None:
        for s in sessions:
            registrations[s.session_id] = geo_registration.solve_registration(s.geo_ref_events, *origin)
        result.session_registrations = {sid: r.to_json() for sid, r in registrations.items()}
    world_sessions = [s for s in sessions
                      if registrations.get(s.session_id) and registrations[s.session_id].world_eligible]

    if world_sessions:
        return _fuse_world_registered(sessions, world_sessions, registrations, origin, result)

    return _fuse_legacy(sessions, result)


def _fuse_world_registered(
    sessions: list[SessionFusionInput],
    world_sessions: list[SessionFusionInput],
    registrations: dict,
    origin: tuple[float, float],
    result: FusionResult,
) -> FusionResult:
    accumulated_points: np.ndarray | None = None
    accumulated_colors: np.ndarray | None = None

    for session in world_sessions:
        reg = registrations[session.session_id]
        seed = reg.primary.transform()
        transform = seed
        if accumulated_points is not None:
            # ICP refine within the registration's own accuracy envelope; a
            # rejected refinement keeps the solved placement (it already
            # passed the contract's gates) rather than dropping the session.
            refinement = register_session_into_cell(
                accumulated_points, session.points, initial_transform=seed)
            if refinement.accepted:
                transform = refinement.transform

        transformed = frame_io.apply_transform(session.points, transform)
        if accumulated_points is None:
            accumulated_points, accumulated_colors = transformed, session.colors
        else:
            merged = frame_io.merge_session_point_clouds(
                [frame_io.PointCloudArrays(points=accumulated_points, colors=accumulated_colors),
                 frame_io.PointCloudArrays(points=transformed, colors=session.colors)],
                voxel_size=MERGE_VOXEL_SIZE_METERS,
            )
            accumulated_points, accumulated_colors = merged.points, merged.colors
        result.accepted_session_ids.append(session.session_id)
        result.session_transforms[session.session_id] = transform
        print(f"world registration: {session.session_id} placed "
              f"(theta {np.degrees(reg.primary.theta_rad):.1f}deg, residual {reg.primary.residual_m:.2f}m)")

    for session in sessions:
        if session.session_id not in result.session_transforms:
            result.rejected_session_ids.append(session.session_id)
            reg = registrations.get(session.session_id)
            print(f"world registration: {session.session_id} LOCAL-ONLY "
                  f"({reg.reason if reg else 'no geoRefEvents'}) -- excluded from world merge")

    result.merged_points = accumulated_points if accumulated_points is not None else np.zeros((0, 3))
    result.merged_colors = accumulated_colors if accumulated_colors is not None else np.zeros((0, 3))
    result.activated = len(result.accepted_session_ids) >= MIN_SESSIONS_FOR_ACTIVATION
    result.anchor_lat, result.anchor_lng = origin
    # The frame is ENU at the origin (+X=east, -Z=north) by construction.
    result.north_aligned = True
    result.north_rms_m = registrations[world_sessions[0].session_id].residual_m
    result.world_eligible = True
    return result


def _fuse_legacy(sessions: list[SessionFusionInput], result: FusionResult) -> FusionResult:
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

    # ── True-north alignment (Blake 2026-07-11: "fix the arbitrary yaw") ──
    # The cell frame so far = the anchor session's ARKit frame: gravity-
    # aligned but with arbitrary heading. Fit the anchor's ARKit ground
    # track to its GPS track (both metric) for the yaw, then rotate the
    # WHOLE cell frame — merged cloud and every session transform — so
    # +X=east, -Z=north. Origin stays at the anchor's ARKit start, whose
    # lat/lng is recorded for cross-cell geo-referencing.
    if result.accepted_session_ids:
        anchor = next(s for s in sessions if s.session_id == result.accepted_session_ids[0])
        result.anchor_lat, result.anchor_lng = anchor.anchor_lat, anchor.anchor_lng
        pairs = _track_gps_pairs(anchor)
        if len(pairs) >= 5:
            from gaussian_ply import fit_yaw_to_north

            arkit_xz = np.array([[p[0][0], p[0][2]] for p in pairs])
            enu = np.array([p[1] for p in pairs])
            R_north, rms = fit_yaw_to_north(arkit_xz, enu)
            gate = max(8.0, 0.35 * float(np.linalg.norm(enu.max(0) - enu.min(0))))
            if rms <= gate:
                result.merged_points = (R_north @ result.merged_points.T).T
                T = np.eye(4); T[:3, :3] = R_north
                for sid in list(result.session_transforms):
                    result.session_transforms[sid] = T @ result.session_transforms[sid]
                result.north_aligned = True
                result.north_rms_m = round(rms, 2)
                print(f"north alignment: yaw fitted from {len(pairs)} track/GPS pairs, rms {rms:.1f}m")
            else:
                print(f"north alignment SKIPPED: fit rms {rms:.1f}m > gate {gate:.1f}m")
    return result


def _track_gps_pairs(session: SessionFusionInput) -> list:
    """Matched (arkit_xyz, (east, north)) samples: GPS path atMs mapped onto
    the sidecar clock via the session-start bridge, nearest track sample
    taken (≤1.5s apart). ENU meters are relative to the anchor lat/lng."""
    if not session.track or not session.gps_path or not session.started_at_ms:
        return []
    t0 = session.track[0][0]
    times = np.array([t for t, _ in session.track])
    pairs = []
    for p in session.gps_path:
        at_ms = p.get("atMs")
        if not at_ms:
            continue
        target_t = t0 + (float(at_ms) - session.started_at_ms) / 1000.0
        i = int(np.argmin(np.abs(times - target_t)))
        if abs(times[i] - target_t) > 1.5:
            continue
        east = (p["lng"] - session.anchor_lng) * 82_300.0
        north = (p["lat"] - session.anchor_lat) * 111_000.0
        pairs.append((session.track[i][1], (east, north)))
    return pairs


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

    # Depth t is boot-clock; the video's pts start at 0 at the chunk's first
    # sidecar frame -- rebase before matching (see SidecarFrame.t_video).
    epoch = sidecar_frames[0].t
    color_imgs = chunk_io.extract_video_frames_at(video_bytes, [d.t - epoch for d, _ in usable])

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
    track: list = []
    for index in sorted(chunks):
        kinds = chunks[index]
        if "jsonl" not in kinds or "depth" not in kinds:
            continue  # no pose or no depth for this chunk -- nothing to fuse
        video_bytes = r2_store.download_video_bytes(kinds["mov"]) if "mov" in kinds else None
        sidecar_bytes = r2_store.download_bytes(kinds["jsonl"])
        depth_bytes = r2_store.download_bytes(kinds["depth"])

        _header, sidecar_frames = chunk_io.parse_sidecar(sidecar_bytes)
        if anchor_xyz is None and sidecar_frames:
            anchor_xyz = sidecar_frames[0].camera_transform[:3, 3]
        track.extend((f.t, f.camera_transform[:3, 3].astype(np.float64)) for f in sidecar_frames[::10])

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
        track=track,
        gps_path=path,
        started_at_ms=float(manifest.get("startedAtMs") or 0),
        geo_ref_events=geo_registration.GeoRef.from_manifest(manifest),
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
    splat_bytes: bytes | None = None
    trainer_reason: str | None = None
    if splat_wanted:
        # Default engine is gsplat (trains from ARKit poses in the cell
        # frame — geo-true by construction). SPLAT_ENGINE=world-mirror
        # restores the legacy feed-forward path, which needs Sim3 fitting
        # and proved unreliable at street scale (2026-07-11 forensics).
        engine = os.environ.get("SPLAT_ENGINE", "gsplat")
        if engine == "gsplat":
            import gsplat_runner  # deferred: keeps plyfile/torch-venv
            # checks out of point-cloud-only callers (e.g. the test suite).

            splat_bytes, trainer_reason = gsplat_runner.train_gsplat_for_cell(cell_id, result, manifests_by_session_id)
        else:
            import splat_train

            splat_bytes, trainer_reason = splat_train.train_splat_for_cell(cell_id, result, manifests_by_session_id)

    result.splat_skip_reason = resolve_splat_outcome(splat_wanted, splat_bytes, trainer_reason)
    if splat_wanted:
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
