"""CPU-only contracts for deterministic reconstruction validation views."""
from __future__ import annotations

import importlib
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image
import pytest


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from render_validation import (  # noqa: E402
    build_contact_sheet,
    build_view_spec,
    captured_image_metrics,
    interpolation_flicker_metrics,
    validation_view_spec_from_dict,
    write_validation_metrics,
)


def linear_camera_path(count: int, spacing: float = 0.25) -> np.ndarray:
    poses = np.repeat(np.eye(4, dtype=np.float64)[None], count, axis=0)
    poses[:, 0, 3] = np.arange(count) * spacing
    return poses


def test_view_spec_separates_arc_length_recorded_and_every_fifth_withheld_pose():
    """Catches index sampling or leakage of a fifth eligible pose into training."""
    poses = linear_camera_path(20)
    poses[:, 0, 3] = np.square(np.arange(20)) * 0.01

    spec = build_view_spec(poses, seed=7, recorded_count=10)

    assert spec.recorded == (0, 6, 9, 11, 14, 16, 17, 18)
    assert spec.withheld == (13, 19)
    assert set(spec.recorded).isdisjoint(spec.withheld)


def test_interpolation_only_uses_adjacent_nearby_low_rotation_poses():
    """Catches interpolation across spatial jumps, sharp turns, or non-adjacent poses."""
    poses = linear_camera_path(6)
    poses[3:, 0, 3] += 2.0
    angle = np.deg2rad(25.0)
    poses[5, :3, :3] = np.array(
        [[np.cos(angle), -np.sin(angle), 0.0],
         [np.sin(angle), np.cos(angle), 0.0],
         [0.0, 0.0, 1.0]]
    )

    spec = build_view_spec(poses, seed=3, recorded_count=6)

    pairs = [(view.start_index, view.end_index) for view in spec.interpolated]
    assert pairs == [(0, 1), (1, 2), (3, 4)]
    assert all(end == start + 1 for start, end in pairs)
    assert all(0.0 < view.alpha < 1.0 for view in spec.interpolated)


def test_standardized_views_are_deterministic_and_absence_is_explicit():
    """Catches invented semantic views and nondeterministic selection among tagged frames."""
    tags = {
        1: ["facade", "vehicle"],
        4: ["sidewalk"],
        7: ["facade"],
        9: ["ground-facing"],
    }

    first = build_view_spec(linear_camera_path(10), seed=11, frame_tags=tags)
    second = build_view_spec(linear_camera_path(10), seed=11, frame_tags=tags)

    assert first.standardized == second.standardized
    by_category = {view.category: view for view in first.standardized}
    assert tuple(by_category) == (
        "sidewalk", "facade", "vegetation", "vehicle", "ground"
    )
    assert by_category["sidewalk"].status == "observed"
    assert by_category["sidewalk"].frame_index == 4
    assert by_category["facade"].frame_index in (1, 7)
    assert by_category["vehicle"].frame_index == 1
    assert by_category["ground"].frame_index == 9
    assert by_category["vegetation"].status == "not_observed"
    assert by_category["vegetation"].frame_index is None


def test_captured_metrics_measure_identical_and_changed_images():
    """Catches invalid PSNR/SSIM direction or failure to compare captured pixels."""
    reference = np.full((16, 16, 3), 0.5, dtype=np.float64)
    changed = reference.copy()
    changed[4:12, 4:12] = 0.0

    identical = captured_image_metrics(reference, reference)
    different = captured_image_metrics(reference, changed)

    assert identical["psnr"] == float("inf")
    assert identical["ssim"] == 1.0
    assert different["psnr"] < 20.0
    assert 0.0 <= different["ssim"] < 1.0


def test_captured_metrics_treat_ordinary_integer_arrays_as_eight_bit_pixels():
    """Catches Python integer arrays being normalized by the int64 storage limit."""
    uint8_image = np.full((4, 4, 3), 128, dtype=np.uint8)
    python_integer_image = np.full((4, 4, 3), 128, dtype=np.int64)

    metrics = captured_image_metrics(uint8_image, python_integer_image)

    assert metrics == {"psnr": float("inf"), "ssim": 1.0}


def test_windowed_ssim_detects_a_one_pixel_structural_displacement():
    """Catches replacement of spatial 11x11 SSIM with global image statistics."""
    reference = np.zeros((32, 32, 3), dtype=np.float64)
    reference[:, 16:] = 1.0
    shifted = np.roll(reference, 1, axis=1)

    metrics = captured_image_metrics(reference, shifted)

    assert metrics["ssim"] < 0.7


def test_interpolation_flicker_reports_color_and_depth_separately():
    """Catches omission or conflation of temporal RGB and depth instability."""
    colors = [
        np.zeros((2, 2, 3), dtype=np.float64),
        np.full((2, 2, 3), 0.2, dtype=np.float64),
        np.full((2, 2, 3), 0.5, dtype=np.float64),
    ]
    depths = [
        np.ones((2, 2), dtype=np.float64),
        np.full((2, 2), 1.5, dtype=np.float64),
        np.full((2, 2), 1.5, dtype=np.float64),
    ]

    metrics = interpolation_flicker_metrics(colors, depths)

    assert metrics == {"color_flicker": 0.25, "depth_flicker": 0.25}


def test_validation_metrics_serialize_identical_view_psnr_explicitly(tmp_path):
    """Catches perfect-view infinite PSNR producing invalid or unwritable JSON."""
    output = tmp_path / "validation-metrics.json"

    write_validation_metrics({"captured": [{"psnr": float("inf")}]}, output)

    assert json.loads(output.read_text()) == {"captured": [{"psnr": "Infinity"}]}


@pytest.mark.parametrize(
    ("document", "message"),
    [
        ({"captured": [{"psnr": float("nan")}]}, "non-finite metric"),
        ({"captured": [{"psnr": -float("inf")}]}, "non-finite metric"),
        ({"captured": [{"ssim": float("inf")}]}, "non-finite metric"),
    ],
)
def test_validation_metrics_reject_unapproved_nonfinite_values(
    tmp_path, document, message
):
    """Catches arbitrary non-finite metrics being disguised as JSON strings."""
    with pytest.raises(ValueError, match=message):
        write_validation_metrics(document, tmp_path / "validation-metrics.json")


def test_pose_validation_rejects_a_non_homogeneous_last_row():
    """Catches affine/projective matrices passing as rigid camera transforms."""
    poses = linear_camera_path(2)
    poses[1, 3] = [0.0, 0.0, 0.25, 1.0]

    with pytest.raises(ValueError, match="homogeneous row"):
        build_view_spec(poses)


@pytest.mark.parametrize("bad_index", [True, 1.5, -1, 4])
def test_semantic_frame_indices_reject_ambiguous_or_out_of_range_values(bad_index):
    """Catches bool/fractional/range coercion selecting the wrong semantic frame."""
    with pytest.raises(ValueError, match="semantic frame index"):
        build_view_spec(
            linear_camera_path(4),
            frame_tags=[{"frameIndex": bad_index, "tags": ["facade"]}],
        )


def test_semantic_frame_indices_and_categories_reject_duplicates_and_malformed_values():
    """Catches duplicate frames/categories overwriting evidence or inventing presets."""
    poses = linear_camera_path(4)
    with pytest.raises(ValueError, match="duplicate semantic frame index"):
        build_view_spec(
            poses,
            frame_tags=[
                {"frameIndex": 1, "tags": ["facade"]},
                {"frameIndex": 1, "tags": ["vehicle"]},
            ],
        )
    with pytest.raises(ValueError, match="semantic category"):
        build_view_spec(poses, frame_tags={1: ["facade", "not-a-category"]})
    with pytest.raises(ValueError, match="duplicate semantic category"):
        build_view_spec(poses, frame_tags={1: ["facade", "facade"]})


def test_persisted_spec_rejects_overlapping_or_incomplete_partitions():
    """Catches a tampered split allowing held-out frames back into training."""
    document = build_view_spec(
        linear_camera_path(5), recorded_count=5
    ).to_dict()
    document["recorded"].append(document["withheld"][0])

    with pytest.raises(ValueError, match="partition"):
        validation_view_spec_from_dict(document, frame_count=5)


def test_contact_sheet_has_explicit_column_and_frame_metric_labels(tmp_path):
    """Catches an unlabeled review artifact whose panels cannot be audited."""
    ground_truth = np.zeros((12, 16, 3), dtype=np.uint8)
    rendered = np.full((12, 16, 3), 64, dtype=np.uint8)
    output = tmp_path / "validation-contact-sheet.jpg"

    labels = build_contact_sheet(
        [{
            "frame_id": "frame-0042",
            "ground_truth": ground_truth,
            "balanced_render": rendered,
            "psnr": 12.04,
            "ssim": 0.75,
        }],
        output,
    )

    assert output.is_file()
    assert Image.open(output).width == ground_truth.shape[1] * 3
    assert labels == (
        "Ground truth",
        "Balanced render",
        "Absolute error",
        "frame-0042 | PSNR 12.04 dB | SSIM 0.7500",
    )


def test_importing_validation_and_legacy_renderer_is_torch_free(monkeypatch):
    """Catches either renderer taking ownership of Torch/CUDA during import."""
    sys.modules.pop("render_validation", None)
    sys.modules.pop("render_ply", None)
    sys.modules.pop("torch", None)

    importlib.import_module("render_validation")
    importlib.import_module("render_ply")

    assert "torch" not in sys.modules
    assert "gsplat" not in sys.modules
