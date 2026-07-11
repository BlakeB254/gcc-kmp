import io

import numpy as np
import pytest
from plyfile import PlyData, PlyElement
from scipy.spatial.transform import Rotation

from gaussian_ply import concatenate_gaussian_ply_bytes, transform_gaussian_ply_bytes


def _make_gaussian_ply_bytes(positions: np.ndarray, quats_wxyz: np.ndarray) -> bytes:
    n = positions.shape[0]
    dtype = [
        ("x", "f4"), ("y", "f4"), ("z", "f4"),
        ("f_dc_0", "f4"), ("f_dc_1", "f4"), ("f_dc_2", "f4"),
        ("opacity", "f4"),
        ("scale_0", "f4"), ("scale_1", "f4"), ("scale_2", "f4"),
        ("rot_0", "f4"), ("rot_1", "f4"), ("rot_2", "f4"), ("rot_3", "f4"),
    ]
    data = np.zeros(n, dtype=dtype)
    data["x"], data["y"], data["z"] = positions[:, 0], positions[:, 1], positions[:, 2]
    data["f_dc_0"] = np.linspace(0, 1, n)
    data["opacity"] = 0.9
    data["scale_0"] = data["scale_1"] = data["scale_2"] = 0.05
    data["rot_0"], data["rot_1"], data["rot_2"], data["rot_3"] = (
        quats_wxyz[:, 0], quats_wxyz[:, 1], quats_wxyz[:, 2], quats_wxyz[:, 3]
    )
    out = io.BytesIO()
    PlyData([PlyElement.describe(data, "vertex")]).write(out)
    return out.getvalue()


def _read_positions_and_quats(ply_bytes: bytes) -> tuple[np.ndarray, np.ndarray]:
    ply = PlyData.read(io.BytesIO(ply_bytes))
    v = ply["vertex"].data
    positions = np.stack([v["x"], v["y"], v["z"]], axis=1)
    quats_wxyz = np.stack([v["rot_0"], v["rot_1"], v["rot_2"], v["rot_3"]], axis=1)
    return positions, quats_wxyz


def test_transform_moves_positions_correctly():
    positions = np.array([[0.0, 0.0, 0.0], [1.0, 0.0, 0.0]])
    identity_quats = np.tile([1.0, 0.0, 0.0, 0.0], (2, 1))  # w,x,y,z identity
    ply_bytes = _make_gaussian_ply_bytes(positions, identity_quats)

    transform = np.eye(4)
    transform[:3, 3] = [5.0, 10.0, -2.0]

    transformed = transform_gaussian_ply_bytes(ply_bytes, transform)
    new_positions, _ = _read_positions_and_quats(transformed)

    assert np.allclose(new_positions, positions + np.array([5.0, 10.0, -2.0]), atol=1e-5)


def test_transform_rotates_orientation_consistently_with_position():
    """A 90-degree rotation about Y should rotate both a Gaussian's position
    (if offset from origin) AND its stored orientation quaternion by the
    same rotation -- verify by converting the transformed quaternion back to
    a rotation and checking it equals (transform_rotation * original)."""
    positions = np.array([[1.0, 0.0, 0.0]])
    original_rotation = Rotation.from_euler("xyz", [0, 0, 30], degrees=True)
    original_quat_xyzw = original_rotation.as_quat()
    original_quat_wxyz = original_quat_xyzw[[3, 0, 1, 2]].reshape(1, 4)
    ply_bytes = _make_gaussian_ply_bytes(positions, original_quat_wxyz)

    theta = np.radians(90)
    transform = np.eye(4)
    transform[:3, :3] = [[np.cos(theta), 0, np.sin(theta)], [0, 1, 0], [-np.sin(theta), 0, np.cos(theta)]]

    transformed = transform_gaussian_ply_bytes(ply_bytes, transform)
    new_positions, new_quats_wxyz = _read_positions_and_quats(transformed)

    # Position: (1,0,0) rotated 90deg about Y -> (0,0,-1)
    assert np.allclose(new_positions[0], [0.0, 0.0, -1.0], atol=1e-5)

    # Orientation: composed rotation should equal transform_rotation * original_rotation
    expected_rotation = Rotation.from_matrix(transform[:3, :3]) * original_rotation
    new_quat_xyzw = new_quats_wxyz[0][[1, 2, 3, 0]]
    actual_rotation = Rotation.from_quat(new_quat_xyzw)
    relative = expected_rotation.inv() * actual_rotation
    assert relative.magnitude() < 1e-4  # near-identity difference


def test_non_position_properties_pass_through_unchanged():
    positions = np.array([[0.0, 0.0, 0.0]])
    quats = np.array([[1.0, 0.0, 0.0, 0.0]])
    ply_bytes = _make_gaussian_ply_bytes(positions, quats)

    transformed = transform_gaussian_ply_bytes(ply_bytes, np.eye(4))

    original = PlyData.read(io.BytesIO(ply_bytes))["vertex"].data
    result = PlyData.read(io.BytesIO(transformed))["vertex"].data
    for prop in ("f_dc_0", "f_dc_1", "f_dc_2", "opacity", "scale_0", "scale_1", "scale_2"):
        assert np.allclose(original[prop], result[prop])


def test_concatenate_merges_vertex_counts():
    a = _make_gaussian_ply_bytes(np.array([[0.0, 0.0, 0.0]]), np.tile([1.0, 0, 0, 0], (1, 1)))
    b = _make_gaussian_ply_bytes(np.array([[1.0, 1.0, 1.0], [2.0, 2.0, 2.0]]), np.tile([1.0, 0, 0, 0], (2, 1)))

    merged = concatenate_gaussian_ply_bytes([a, b])
    positions, _ = _read_positions_and_quats(merged)
    assert positions.shape[0] == 3


def test_concatenate_rejects_mismatched_properties():
    a = _make_gaussian_ply_bytes(np.array([[0.0, 0.0, 0.0]]), np.tile([1.0, 0, 0, 0], (1, 1)))
    # A minimal, differently-shaped PLY (no rotation/scale/opacity fields).
    dtype = [("x", "f4"), ("y", "f4"), ("z", "f4")]
    data = np.zeros(1, dtype=dtype)
    out = io.BytesIO()
    PlyData([PlyElement.describe(data, "vertex")]).write(out)
    b = out.getvalue()

    with pytest.raises(ValueError):
        concatenate_gaussian_ply_bytes([a, b])


def test_umeyama_recovers_known_similarity():
    from scipy.spatial.transform import Rotation

    from gaussian_ply import umeyama_sim3

    rng = np.random.default_rng(7)
    src = rng.uniform(-5, 5, (12, 3))
    R_true = Rotation.from_euler("xyz", [20, -35, 60], degrees=True).as_matrix()
    s_true, t_true = 2.4, np.array([1.5, -3.0, 0.7])
    dst = s_true * (R_true @ src.T).T + t_true

    s, R, t, rms = umeyama_sim3(src, dst)
    assert abs(s - s_true) < 1e-9
    assert np.allclose(R, R_true, atol=1e-9)
    assert np.allclose(t, t_true, atol=1e-9)
    assert rms < 1e-9


def test_sim3_transform_scales_positions_and_log_scales():
    import io

    import numpy as _np
    from plyfile import PlyData

    from gaussian_ply import sim3_transform_gaussian_ply_bytes

    positions = _np.array([[0.0, 0.0, 0.0], [1.0, 2.0, 3.0], [-1.0, 0.5, 2.0]])
    quats = _np.tile(_np.array([1.0, 0.0, 0.0, 0.0]), (3, 1))
    ply_bytes = _make_gaussian_ply_bytes(positions, quats)
    s = 2.0
    R = _np.eye(3)
    t = _np.array([10.0, 0.0, 0.0])
    out = sim3_transform_gaussian_ply_bytes(ply_bytes, s, R, t)

    before = PlyData.read(io.BytesIO(ply_bytes))["vertex"].data
    after = PlyData.read(io.BytesIO(out))["vertex"].data
    assert _np.allclose(after["x"], before["x"] * 2.0 + 10.0, atol=1e-5)
    assert _np.allclose(after["scale_0"], before["scale_0"] + _np.log(2.0), atol=1e-6)
