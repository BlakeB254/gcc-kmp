"""Rigid-transforms a 3D Gaussian Splat .ply (position + orientation per
Gaussian, matching the standard gsplat/INRIA export convention) and
concatenates multiple splats into one merged file.

Why this exists: cdx-world-mirror's HY-WorldMirror-2.0 pipeline does a
single joint solve over whatever images it's given, with no persistent map
or cross-session registration (confirmed in
docs/superpowers/specs/2026-07-03-phase3-reconstruction-fusion-research.md
§1) -- so it's only trustworthy run PER SESSION (one continuous walk,
already internally consistent via ARKit), not blindly over frames pooled
from multiple independent sessions. Cross-session alignment is this
pipeline's own job (registration.py's GPS-seeded ICP, already computed by
the time splat_train.py needs it) -- this module applies that already-
computed rigid transform to each session's independently-generated splat so
they land in the same shared cell coordinate frame, the same way
frame_io.apply_transform does for point clouds.

A rigid transform (rotation + translation, no scale/shear/reflection, which
is exactly what registration.py ever produces) is safe to apply to a
Gaussian's position and orientation without touching its color (spherical
harmonics) or shape (scale) — those are defined relative to the Gaussian's
own local frame, which rotates rigidly along with it.

Property layout and scalar-first (w,x,y,z) `rot_0..rot_3` convention
CONFIRMED 2026-07-07 against a real gaussians.ply from a live cdx-world-mirror
smoke test (x,y,z,nx,ny,nz,f_dc_0-2,opacity,scale_0-2,rot_0-3, quaternion
norms ~1.0) -- this module's transform_gaussian_ply_bytes/
concatenate_gaussian_ply_bytes were run against that real file, not just
synthetic fixtures, and produced correctly-shaped output (see README).
"""
from __future__ import annotations

import numpy as np
from plyfile import PlyData, PlyElement
from scipy.spatial.transform import Rotation


def transform_gaussian_ply_bytes(ply_bytes: bytes, transform_4x4: np.ndarray) -> bytes:
    """Applies transform_4x4 (rotation + translation) to every Gaussian's
    position and orientation. Every other per-Gaussian property (opacity,
    scale, spherical-harmonic color coefficients, ...) passes through
    unchanged, whatever they're named -- this doesn't need to know the SH
    degree or exact property list to be correct."""
    import io

    ply = PlyData.read(io.BytesIO(ply_bytes))
    vertex = ply["vertex"]
    data = vertex.data.copy()

    positions = np.stack([data["x"], data["y"], data["z"]], axis=1).astype(np.float64)
    homogeneous = np.hstack([positions, np.ones((positions.shape[0], 1))])
    new_positions = (transform_4x4 @ homogeneous.T).T[:, :3]
    data["x"] = new_positions[:, 0].astype(data["x"].dtype)
    data["y"] = new_positions[:, 1].astype(data["y"].dtype)
    data["z"] = new_positions[:, 2].astype(data["z"].dtype)

    rotation_props = ("rot_0", "rot_1", "rot_2", "rot_3")
    if all(p in vertex.data.dtype.names for p in rotation_props):
        transform_rotation = Rotation.from_matrix(transform_4x4[:3, :3])
        # rot_0..rot_3 = (w, x, y, z) scalar-first -- scipy wants (x,y,z,w).
        quats_wxyz = np.stack([data[p] for p in rotation_props], axis=1).astype(np.float64)
        quats_xyzw = quats_wxyz[:, [1, 2, 3, 0]]
        original_rotations = Rotation.from_quat(quats_xyzw)
        new_rotations = transform_rotation * original_rotations
        new_quats_xyzw = new_rotations.as_quat()
        new_quats_wxyz = new_quats_xyzw[:, [3, 0, 1, 2]]
        for i, prop in enumerate(rotation_props):
            data[prop] = new_quats_wxyz[:, i].astype(data[prop].dtype)

    out = io.BytesIO()
    PlyData([PlyElement.describe(data, "vertex")], text=ply.text, byte_order=ply.byte_order).write(out)
    return out.getvalue()


def concatenate_gaussian_ply_bytes(ply_bytes_list: list[bytes]) -> bytes:
    """Merges multiple (already co-aligned, e.g. via transform_gaussian_ply_bytes)
    splats' vertex data into one .ply. Requires identical property sets --
    raises rather than silently dropping fields if they differ, since a
    silent partial merge would be worse than a loud failure here."""
    import io

    if not ply_bytes_list:
        raise ValueError("no splats to concatenate")

    parsed = [PlyData.read(io.BytesIO(b)) for b in ply_bytes_list]
    first_names = parsed[0]["vertex"].data.dtype.names
    for p in parsed[1:]:
        if p["vertex"].data.dtype.names != first_names:
            raise ValueError(
                f"splat property mismatch: {first_names} vs {p['vertex'].data.dtype.names} "
                "-- can't concatenate splats from different pipeline versions"
            )

    merged = np.concatenate([p["vertex"].data for p in parsed])
    out = io.BytesIO()
    PlyData([PlyElement.describe(merged, "vertex")], text=parsed[0].text, byte_order=parsed[0].byte_order).write(out)
    return out.getvalue()


def umeyama_sim3(src: np.ndarray, dst: np.ndarray) -> tuple[float, np.ndarray, np.ndarray, float]:
    """Least-squares similarity fit (Umeyama 1991): returns (s, R, t, rms)
    such that dst ≈ s · R @ src + t. Needs ≥3 non-degenerate points.

    Used to map a world-mirror reconstruction (its own arbitrary frame AND
    scale) into the ARKit session frame, by fitting the world-mirror camera
    centers to the ARKit camera positions of the SAME submitted frames —
    the fix for merged community splats not sharing a ground plane."""
    src = np.asarray(src, dtype=np.float64)
    dst = np.asarray(dst, dtype=np.float64)
    assert src.shape == dst.shape and src.shape[0] >= 3, "need >=3 correspondences"
    mu_s, mu_d = src.mean(axis=0), dst.mean(axis=0)
    xs, xd = src - mu_s, dst - mu_d
    cov = xd.T @ xs / len(src)
    U, D, Vt = np.linalg.svd(cov)
    S = np.eye(3)
    if np.linalg.det(U) * np.linalg.det(Vt) < 0:
        S[2, 2] = -1.0
    R = U @ S @ Vt
    var_s = (xs ** 2).sum() / len(src)
    s = float(np.trace(np.diag(D) @ S) / var_s) if var_s > 0 else 1.0
    t = mu_d - s * R @ mu_s
    rms = float(np.sqrt(((dst - (s * (R @ src.T).T + t)) ** 2).sum(axis=1).mean()))
    return s, R, t, rms


def sim3_transform_gaussian_ply_bytes(ply_bytes: bytes, s: float, R: np.ndarray, t: np.ndarray) -> bytes:
    """Similarity transform of a Gaussian splat: positions p' = s·R·p + t,
    orientations rotated by R, and per-Gaussian LOG scales shifted by ln(s)
    (the standard 3DGS convention stores scale_0..2 as logs)."""
    import io

    from plyfile import PlyData, PlyElement

    ply = PlyData.read(io.BytesIO(ply_bytes))
    vertex = ply["vertex"]
    data = vertex.data.copy()

    positions = np.stack([data["x"], data["y"], data["z"]], axis=1).astype(np.float64)
    new_positions = s * (R @ positions.T).T + t
    for i, axis in enumerate(("x", "y", "z")):
        data[axis] = new_positions[:, i].astype(data[axis].dtype)

    rotation_props = ("rot_0", "rot_1", "rot_2", "rot_3")
    if all(p in vertex.data.dtype.names for p in rotation_props):
        rot = Rotation.from_matrix(R)
        quats_wxyz = np.stack([data[p] for p in rotation_props], axis=1).astype(np.float64)
        new_q = (rot * Rotation.from_quat(quats_wxyz[:, [1, 2, 3, 0]])).as_quat()[:, [3, 0, 1, 2]]
        for i, prop in enumerate(rotation_props):
            data[prop] = new_q[:, i].astype(data[prop].dtype)

    log_s = float(np.log(s))
    for prop in ("scale_0", "scale_1", "scale_2"):
        if prop in vertex.data.dtype.names:
            data[prop] = (data[prop].astype(np.float64) + log_s).astype(data[prop].dtype)

    out = io.BytesIO()
    PlyData([PlyElement.describe(data, "vertex")], text=ply.text, byte_order=ply.byte_order).write(out)
    return out.getvalue()
