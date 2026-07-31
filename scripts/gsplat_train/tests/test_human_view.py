from __future__ import annotations

import json
import unittest
from pathlib import Path
import sys

import numpy as np
from plyfile import PlyData, PlyElement

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    from human_view import normalize_for_human_view
except ImportError:
    normalize_for_human_view = None


def _write_fixture(path) -> None:
    dtype = [
        ("x", "f4"), ("y", "f4"), ("z", "f4"),
        ("f_dc_0", "f4"), ("f_dc_1", "f4"), ("f_dc_2", "f4"),
        ("opacity", "f4"),
        ("scale_0", "f4"), ("scale_1", "f4"), ("scale_2", "f4"),
        ("rot_0", "f4"), ("rot_1", "f4"), ("rot_2", "f4"), ("rot_3", "f4"),
    ]
    vertices = np.zeros(3, dtype=dtype)
    vertices["x"] = [0.0, 1.0, 2.0]
    vertices["y"] = [2.0, 2.1, 2.2]
    vertices["z"] = [0.0, -1.0, -2.0]
    vertices["opacity"] = 2.0
    vertices["scale_0"] = np.log([0.02, 0.02, 0.01])
    vertices["scale_1"] = np.log([0.02, 0.02, 0.01])
    vertices["scale_2"] = np.log([0.02, 0.02, 0.20])
    vertices["rot_0"] = 1.0
    PlyData([PlyElement.describe(vertices, "vertex")]).write(path)


class HumanViewTest(unittest.TestCase):
    def test_rebases_ground_and_removes_spiky_gaussians(self):
        """Catches missing ground translation or an ineffective anisotropy gate."""
        self.assertTrue(callable(normalize_for_human_view), "normalization behavior is missing")
        with self.subTest(), __import__("tempfile").TemporaryDirectory() as td:
            tmp_path = Path(td)
            source = tmp_path / "raw.ply"
            output = tmp_path / "human.ply"
            poses_path = tmp_path / "poses.npy"
            metadata_path = tmp_path / "human.json"
            _write_fixture(source)
            poses = np.repeat(np.eye(4, dtype=np.float32)[None], 3, axis=0)
            poses[:, 1, 3] = [1.7, 1.8, 1.9]
            poses[:, 2, 3] = [0.0, -1.0, -2.0]
            np.save(poses_path, poses)

            result = normalize_for_human_view(
                source, output, poses_path=poses_path,
                metadata_path=metadata_path, eye_height=1.65,
                max_anisotropy=8.0,
            )

            vertices = PlyData.read(output)["vertex"].data
            self.assertEqual(len(vertices), 2)
            self.assertTrue(np.allclose(vertices["y"], [1.85, 1.95], atol=1e-5))
            self.assertEqual(result["removed"]["anisotropy"], 1)
            metadata = json.loads(metadata_path.read_text())
            self.assertEqual(metadata["coordinateSystem"], "metric-y-up-north-aligned")
            self.assertEqual(metadata["viewer"]["position"], [0.0, 1.65, 0.0])
            self.assertEqual(metadata["viewer"]["target"], [0.0, 1.65, -2.0])

    def test_source_is_not_modified(self):
        """Catches accidental in-place modification of the archival reconstruction."""
        self.assertTrue(callable(normalize_for_human_view), "normalization behavior is missing")
        with __import__("tempfile").TemporaryDirectory() as td:
            tmp_path = Path(td)
            source = tmp_path / "raw.ply"
            output = tmp_path / "human.ply"
            poses_path = tmp_path / "poses.npy"
            _write_fixture(source)
            before = source.read_bytes()
            poses = np.eye(4, dtype=np.float32)[None]
            poses[0, 1, 3] = 1.65
            np.save(poses_path, poses)

            normalize_for_human_view(source, output, poses_path=poses_path)

            self.assertEqual(source.read_bytes(), before)
            self.assertNotEqual(output, source)


if __name__ == "__main__":
    unittest.main()
