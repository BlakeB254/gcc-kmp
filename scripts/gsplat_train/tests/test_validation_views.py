"""CPU-only contracts for deterministic reconstruction validation views."""
from __future__ import annotations

import gc
import importlib
import hashlib
import json
import sys
import weakref
from pathlib import Path

import numpy as np
from PIL import Image
import pytest


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import render_validation as validation_module  # noqa: E402
from render_validation import (  # noqa: E402
    aggregate_captured_metrics,
    build_contact_sheet,
    build_view_spec,
    captured_image_metrics,
    interpolation_flicker_metrics,
    snapshot_validation_comparisons,
    validation_view_spec_from_dict,
    write_validation_metrics,
)


def linear_camera_path(count: int, spacing: float = 0.25) -> np.ndarray:
    poses = np.repeat(np.eye(4, dtype=np.float64)[None], count, axis=0)
    poses[:, 0, 3] = np.arange(count) * spacing
    return poses


def eight_frame_validation_spec():
    return validation_view_spec_from_dict(
        {
            "recorded": [0, 1, 2, 3, 5, 6, 7],
            "withheld": [4],
            "interpolated": [
                {"startIndex": 0, "endIndex": 1, "alpha": 0.5},
                {"startIndex": 6, "endIndex": 7, "alpha": 0.5},
            ],
            "standardized": [],
        },
        frame_count=8,
    )


def complete_captured_comparisons():
    image = np.zeros((4, 4, 3), dtype=np.float64)
    rows = []
    for index in range(8):
        kind = "withheld" if index == 4 else "recorded"
        rows.append(
            {
                "frame_id": f"{kind}-{index:04d}",
                "kind": kind,
                "frame_index": index,
                "ground_truth": image.copy(),
                "balanced_render": image.copy(),
            }
        )
    return rows


def complete_interpolated_comparisons():
    rows = []
    for start_index, end_index in ((0, 1), (6, 7)):
        rows.append(
            {
                "start_index": start_index,
                "end_index": end_index,
                "alpha": 0.5,
                "color_frames": (
                    np.zeros((2, 2, 3), dtype=np.float64),
                    np.full((2, 2, 3), 0.25, dtype=np.float64),
                    np.full((2, 2, 3), 0.5, dtype=np.float64),
                ),
                "depth_frames": (
                    np.ones((2, 2), dtype=np.float64),
                    np.full((2, 2), 1.25, dtype=np.float64),
                    np.full((2, 2), 1.5, dtype=np.float64),
                ),
            }
        )
    return rows


def test_validation_snapshots_are_disk_backed_with_portable_integrity_descriptors(
    tmp_path,
):
    """Catches validation evidence retaining all normalized float64 bytes in RAM."""
    workspace = tmp_path / "validation-evidence"

    snapshots = snapshot_validation_comparisons(
        (row for row in complete_captured_comparisons()),
        interpolated_comparisons=(
            row for row in complete_interpolated_comparisons()
        ),
        workspace=workspace,
    )

    descriptor = snapshots.captured[0].ground_truth
    snapshot_path = snapshots.workspace / descriptor.relative_path
    payload = snapshot_path.read_bytes()
    decoded = np.load(snapshot_path, allow_pickle=False)
    assert snapshots.workspace == workspace.resolve()
    assert descriptor.shape == (4, 4, 3)
    assert descriptor.dtype == "<f8"
    assert descriptor.byte_length == len(payload)
    assert descriptor.sha256 == hashlib.sha256(payload).hexdigest()
    assert decoded.dtype.str == "<f8"
    assert decoded.flags.c_contiguous


def test_metrics_writer_rejects_replaced_disk_evidence_before_publishing(tmp_path):
    """Catches a file replacement changing snapshotted pixels before recomputation."""
    snapshots = snapshot_validation_comparisons(
        complete_captured_comparisons(), workspace=tmp_path / "evidence"
    )
    descriptor = snapshots.captured[0].balanced_render
    snapshot_path = snapshots.workspace / descriptor.relative_path
    payload = bytearray(snapshot_path.read_bytes())
    payload[-1] ^= 1
    replacement = snapshot_path.with_name("replacement.npy")
    replacement.write_bytes(payload)
    replacement.replace(snapshot_path)
    output = tmp_path / "validation-metrics.json"

    with pytest.raises(ValueError, match="SHA-256"):
        write_validation_metrics(
            snapshots, output, view_spec=eight_frame_validation_spec()
        )

    assert not output.exists()


def test_snapshot_stream_releases_each_source_comparison_before_requesting_next(
    tmp_path,
):
    """Catches the snapshot boundary accumulating caller arrays across rows."""
    released_after_yield: list[weakref.ReferenceType[np.ndarray]] = []

    def comparisons():
        for index in range(8):
            gc.collect()
            assert all(reference() is None for reference in released_after_yield)
            released_after_yield.clear()
            ground_truth = np.zeros((4, 4, 3), dtype=np.float64)
            balanced_render = np.zeros((4, 4, 3), dtype=np.float64)
            released_after_yield.extend(
                (weakref.ref(ground_truth), weakref.ref(balanced_render))
            )
            kind = "withheld" if index == 4 else "recorded"
            comparison = {
                "frame_id": f"{kind}-{index:04d}",
                "kind": kind,
                "frame_index": index,
                "ground_truth": ground_truth,
                "balanced_render": balanced_render,
            }
            yield comparison
            del comparison, ground_truth, balanced_render
        gc.collect()
        assert all(reference() is None for reference in released_after_yield)

    snapshots = snapshot_validation_comparisons(
        comparisons(), workspace=tmp_path / "evidence"
    )

    assert len(snapshots.captured) == 8


def test_writer_and_contact_sheet_decode_at_most_one_evidence_comparison(tmp_path):
    """Catches retaining decoded captured rows or interpolation tuples across iterations."""
    snapshots = snapshot_validation_comparisons(
        complete_captured_comparisons(),
        interpolated_comparisons=complete_interpolated_comparisons(),
        workspace=tmp_path / "evidence",
    )
    writer_stats = validation_module.ValidationEvidenceInstrumentation()
    document = write_validation_metrics(
        snapshots,
        tmp_path / "validation-metrics.json",
        view_spec=eight_frame_validation_spec(),
        instrumentation=writer_stats,
    )

    assert writer_stats.max_live_decoded_evidence == 1
    assert writer_stats.live_decoded_evidence == 0
    assert writer_stats.decoded_captured_comparisons == 8
    assert writer_stats.decoded_interpolation_tuples == 2

    contact_stats = validation_module.ValidationEvidenceInstrumentation()
    validation_module.build_contact_sheet_from_snapshots(
        snapshots,
        document,
        tmp_path / "validation-contact-sheet.jpg",
        workspace=tmp_path / "contact-sheet-spool",
        instrumentation=contact_stats,
    )

    assert contact_stats.max_live_decoded_evidence == 1
    assert contact_stats.live_decoded_evidence == 0
    assert contact_stats.decoded_captured_comparisons == 8
    assert contact_stats.decoded_interpolation_tuples == 0


def test_contact_sheet_rejects_metric_labels_modified_after_pixel_recomputation(
    tmp_path,
):
    """Catches mutable returned metrics producing authoritative-looking false labels."""
    snapshots = snapshot_validation_comparisons(
        complete_captured_comparisons(), workspace=tmp_path / "evidence"
    )
    document = write_validation_metrics(
        snapshots,
        tmp_path / "validation-metrics.json",
        view_spec=eight_frame_validation_spec(),
    )
    document["captured"][0]["psnr"] = 3.0
    document["captured"][0]["ssim"] = 0.25
    output = tmp_path / "validation-contact-sheet.jpg"

    with pytest.raises(ValueError, match="metric labels do not match"):
        validation_module.build_contact_sheet_from_snapshots(
            snapshots,
            document,
            output,
            workspace=tmp_path / "contact-sheet-spool",
        )

    assert not output.exists()


def test_view_spec_separates_arc_length_recorded_and_every_fifth_withheld_pose():
    """Catches index sampling or leakage of a fifth eligible pose into training."""
    poses = linear_camera_path(20)
    poses[:, 0, 3] = np.square(np.arange(20)) * 0.01

    spec = build_view_spec(poses, seed=7, recorded_count=10)

    assert spec.recorded == (0, 6, 9, 11, 14, 16, 17, 18)
    assert spec.withheld == (13, 19)
    assert set(spec.recorded).isdisjoint(spec.withheld)


def test_view_spec_rejects_too_few_eligible_views_for_a_real_holdout():
    """Catches tiny datasets silently claiming validation with no held-out image."""
    with pytest.raises(ValueError, match="at least 5 eligible views"):
        build_view_spec(linear_camera_path(4), recorded_count=4)


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

    assert metrics == {"mse": 0.0, "psnr": float("inf"), "ssim": 1.0}


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

    snapshots = snapshot_validation_comparisons(
        complete_captured_comparisons(), workspace=tmp_path / "evidence"
    )
    write_validation_metrics(snapshots, output, view_spec=eight_frame_validation_spec())

    document = json.loads(output.read_text())
    assert document["aggregates"] == {
        "meanMse": 0.0,
        "meanPsnr": "Infinity",
        "meanSsim": 1.0,
    }
    assert document["captured"][0] == {
        "frameId": "recorded-0000",
        "frameIndex": 0,
        "kind": "recorded",
        "mse": 0.0,
        "psnr": "Infinity",
        "ssim": 1.0,
    }
    assert document["expectedCapturedCount"] == 8
    assert len(document["captured"]) == 8
    assert document["interpolated"] == []
    assert document["viewSpec"] == {
        "recorded": [0, 1, 2, 3, 5, 6, 7],
        "withheld": [4],
        "interpolated": [
            {"startIndex": 0, "endIndex": 1, "alpha": 0.5},
            {"startIndex": 6, "endIndex": 7, "alpha": 0.5},
        ],
        "standardized": [],
    }


def test_metrics_writer_rejects_forged_private_report_even_with_checksum(tmp_path):
    """Catches the old object.__new__/private-symbol checksum forgery API."""
    if hasattr(validation_module, "ValidationMetricsReport"):
        report_type = validation_module.ValidationMetricsReport
        evidence_type = validation_module._CapturedMetricEvidence
        forged = object.__new__(report_type)
        forged._captured = tuple(
            evidence_type(
                frame_id=f"recorded-{index:04d}",
                kind="recorded",
                frame_index=index,
                mse=0.0,
                ssim=1.0,
            )
            for index in range(8)
        )
        forged._expected_captured_count = 8
        forged._interpolated = ()
        forged._view_spec_json = None
        forged._provenance = validation_module._METRICS_REPORT_PROVENANCE
        forged._evidence_sha256 = validation_module._metrics_report_evidence_sha256(
            forged
        )
        writer_arguments = {}
    else:
        forged = object()
        writer_arguments = {"view_spec": eight_frame_validation_spec()}

    with pytest.raises((TypeError, ValueError)):
        write_validation_metrics(
            forged, tmp_path / "validation-metrics.json", **writer_arguments
        )


def test_metrics_writer_rejects_incomplete_one_of_eight_captured_coverage(tmp_path):
    """Catches expected coverage being set tautologically from caller rows."""
    snapshots = snapshot_validation_comparisons(
        complete_captured_comparisons()[:1], workspace=tmp_path / "evidence"
    )
    corrupted = snapshots.workspace / snapshots.captured[0].ground_truth.relative_path
    corrupted.write_bytes(b"not a valid snapshot")

    with pytest.raises(ValueError, match="exact captured coverage"):
        write_validation_metrics(
            snapshots,
            tmp_path / "validation-metrics.json",
            view_spec=eight_frame_validation_spec(),
        )


def test_metrics_writer_rejects_extra_frame_index_999(tmp_path):
    """Catches extra comparisons outside the persisted validation partition."""
    rows = complete_captured_comparisons()
    rows.append(
        {
            "frame_id": "recorded-0999",
            "kind": "recorded",
            "frame_index": 999,
            "ground_truth": np.zeros((4, 4, 3), dtype=np.float64),
            "balanced_render": np.zeros((4, 4, 3), dtype=np.float64),
        }
    )
    snapshots = snapshot_validation_comparisons(
        rows, workspace=tmp_path / "evidence"
    )

    with pytest.raises(ValueError, match="exact captured coverage"):
        write_validation_metrics(
            snapshots,
            tmp_path / "validation-metrics.json",
            view_spec=eight_frame_validation_spec(),
        )


@pytest.mark.parametrize(
    "mutate_rows",
    [
        lambda rows: rows.__setitem__(7, dict(rows[0])),
        lambda rows: rows[4].__setitem__("kind", "recorded"),
    ],
    ids=("duplicate-transplant", "wrong-kind"),
)
def test_metrics_writer_rejects_duplicate_transplanted_or_wrong_kind_comparison(
    tmp_path, mutate_rows
):
    """Catches duplicate evidence or a captured frame moved to the wrong split."""
    rows = complete_captured_comparisons()
    mutate_rows(rows)
    snapshots = snapshot_validation_comparisons(
        rows, workspace=tmp_path / "evidence"
    )

    with pytest.raises(ValueError, match="exact captured coverage"):
        write_validation_metrics(
            snapshots,
            tmp_path / "validation-metrics.json",
            view_spec=eight_frame_validation_spec(),
        )


@pytest.mark.parametrize(
    "mutate_rows",
    [
        lambda rows: rows.pop(),
        lambda rows: rows.append(dict(rows[0])),
        lambda rows: rows[0].__setitem__("end_index", 2),
        lambda rows: rows[0].__setitem__("alpha", 0.25),
    ],
    ids=("missing", "duplicate", "wrong-pair", "wrong-alpha"),
)
def test_metrics_writer_requires_exact_interpolated_pair_coverage_when_emitted(
    tmp_path, mutate_rows
):
    """Catches incomplete, duplicate, or transplanted interpolation flicker evidence."""
    interpolation_rows = complete_interpolated_comparisons()
    mutate_rows(interpolation_rows)
    snapshots = snapshot_validation_comparisons(
        complete_captured_comparisons(),
        interpolated_comparisons=interpolation_rows,
        workspace=tmp_path / "evidence",
    )

    with pytest.raises(ValueError, match="exact interpolated coverage"):
        write_validation_metrics(
            snapshots,
            tmp_path / "validation-metrics.json",
            view_spec=eight_frame_validation_spec(),
        )


def test_metric_snapshots_reject_bad_shapes_types_and_nonfinite_depth(tmp_path):
    """Catches malformed pixels or depth entering authoritative metric evidence."""
    captured = complete_captured_comparisons()
    captured[0]["balanced_render"] = np.zeros((3, 4, 3), dtype=np.float64)
    with pytest.raises(ValueError, match="same shape"):
        snapshot_validation_comparisons(
            captured, workspace=tmp_path / "bad-shape"
        )

    captured = complete_captured_comparisons()
    captured[0]["ground_truth"] = np.zeros((4, 4, 3), dtype=np.bool_)
    with pytest.raises(ValueError, match="numeric pixels"):
        snapshot_validation_comparisons(
            captured, workspace=tmp_path / "bad-type"
        )

    interpolation = complete_interpolated_comparisons()
    interpolation[0]["depth_frames"][1][0, 0] = float("nan")
    with pytest.raises(ValueError, match="finite depth"):
        snapshot_validation_comparisons(
            complete_captured_comparisons(),
            interpolated_comparisons=interpolation,
            workspace=tmp_path / "bad-depth",
        )


def test_metrics_writer_uses_immutable_snapshots_after_source_arrays_mutate(tmp_path):
    """Catches caller mutation changing pixels after the evidence boundary."""
    rows = complete_captured_comparisons()
    caller_render = rows[0]["balanced_render"]
    snapshots = snapshot_validation_comparisons(
        rows, workspace=tmp_path / "evidence"
    )
    caller_render[...] = 1.0
    rows[0]["ground_truth"][...] = 0.5

    output = tmp_path / "validation-metrics.json"
    write_validation_metrics(
        snapshots, output, view_spec=eight_frame_validation_spec()
    )

    first = json.loads(output.read_text())["captured"][0]
    assert first["mse"] == 0.0
    assert first["psnr"] == "Infinity"
    assert first["ssim"] == 1.0


def test_metrics_writer_recomputes_interpolation_flicker_from_snapshots(tmp_path):
    """Catches caller-supplied derived flicker metrics bypassing pixel evidence."""
    snapshots = snapshot_validation_comparisons(
        complete_captured_comparisons(),
        interpolated_comparisons=complete_interpolated_comparisons(),
        workspace=tmp_path / "evidence",
    )
    output = tmp_path / "validation-metrics.json"

    write_validation_metrics(
        snapshots, output, view_spec=eight_frame_validation_spec()
    )

    document = json.loads(output.read_text())
    assert document["interpolated"] == [
        {
            "alpha": 0.5,
            "color_flicker": 0.25,
            "depth_flicker": 0.25,
            "endIndex": 1,
            "startIndex": 0,
        },
        {
            "alpha": 0.5,
            "color_flicker": 0.25,
            "depth_flicker": 0.25,
            "endIndex": 7,
            "startIndex": 6,
        },
    ]
    assert document["aggregates"]["meanColorFlicker"] == 0.25
    assert document["aggregates"]["meanDepthFlicker"] == 0.25


def test_writer_rejects_caller_supplied_derived_metric_fields(tmp_path):
    """Catches a reserved Infinity token bypassing recomputation from pixels."""
    rows = complete_captured_comparisons()
    rows[0]["psnr"] = "Infinity"

    with pytest.raises(ValueError, match="fields"):
        snapshot_validation_comparisons(rows, workspace=tmp_path / "evidence")


def test_mixed_pixel_evidence_keeps_aggregate_psnr_finite(tmp_path):
    """Catches a single perfect frame contaminating a mixed aggregate with Infinity."""
    rows = complete_captured_comparisons()
    rows[7]["balanced_render"] = np.full((4, 4, 3), 0.5, dtype=np.float64)
    snapshots = snapshot_validation_comparisons(
        rows, workspace=tmp_path / "evidence"
    )
    output = tmp_path / "validation-metrics.json"

    write_validation_metrics(
        snapshots, output, view_spec=eight_frame_validation_spec()
    )

    document = json.loads(output.read_text())
    assert document["captured"][7]["psnr"] == pytest.approx(6.0205999133)
    assert document["aggregates"]["meanMse"] == pytest.approx(0.03125)
    assert document["aggregates"]["meanPsnr"] == pytest.approx(15.0514997832)
    assert np.isfinite(document["aggregates"]["meanPsnr"])


def test_old_derived_document_shape_is_not_an_accepted_writer_api(tmp_path):
    """Catches a caller passing precomputed MSE/SSIM/Infinity as evidence."""
    forged = {
        "expectedCapturedCount": 8,
        "captured": [{"mse": 0.0, "psnr": "Infinity", "ssim": 1.0}],
        "interpolated": [],
        "aggregates": {
            "meanMse": 0.0,
            "meanPsnr": "Infinity",
            "meanSsim": 1.0,
        },
    }

    with pytest.raises((TypeError, ValueError)):
        write_validation_metrics(
            forged,
            tmp_path / "validation-metrics.json",
            view_spec=eight_frame_validation_spec(),
        )


def test_psnr_aggregate_uses_mse_so_perfect_and_imperfect_frames_stay_finite():
    """Catches one perfect frame forcing a mixed aggregate to positive infinity."""
    reference = np.zeros((4, 4, 3), dtype=np.float64)
    perfect = captured_image_metrics(reference, reference)
    imperfect = captured_image_metrics(reference, np.full_like(reference, 0.5))

    aggregate = aggregate_captured_metrics([perfect, imperfect])

    assert aggregate["meanPsnr"] == pytest.approx(9.0308998699)
    assert np.isfinite(aggregate["meanPsnr"])


def test_pose_validation_rejects_a_non_homogeneous_last_row():
    """Catches affine/projective matrices passing as rigid camera transforms."""
    poses = linear_camera_path(2)
    poses[1, 3] = [0.0, 0.0, 0.25, 1.0]

    with pytest.raises(ValueError, match="homogeneous row"):
        build_view_spec(poses)


@pytest.mark.parametrize("bad_index", [True, 1.5, -1, 5])
def test_semantic_frame_indices_reject_ambiguous_or_out_of_range_values(bad_index):
    """Catches bool/fractional/range coercion selecting the wrong semantic frame."""
    with pytest.raises(ValueError, match="semantic frame index"):
        build_view_spec(
            linear_camera_path(5),
            frame_tags=[{"frameIndex": bad_index, "tags": ["facade"]}],
        )


def test_semantic_frame_indices_and_categories_reject_duplicates_and_malformed_values():
    """Catches duplicate frames/categories overwriting evidence or inventing presets."""
    poses = linear_camera_path(5)
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


def test_persisted_spec_rejects_an_empty_withheld_partition():
    """Catches a split validator accepting recorded-only pseudo-validation."""
    document = {
        "recorded": [0, 1, 2, 3],
        "withheld": [],
        "interpolated": [],
        "standardized": [],
    }

    with pytest.raises(ValueError, match="nonempty withheld partition"):
        validation_view_spec_from_dict(document, frame_count=4)


def test_persisted_spec_rejects_duplicate_interpolated_pairs():
    """Catches an ambiguous expected flicker-coverage set in persisted evidence."""
    document = eight_frame_validation_spec().to_dict()
    document["interpolated"].append(dict(document["interpolated"][0]))

    with pytest.raises(ValueError, match="duplicate interpolated"):
        validation_view_spec_from_dict(document, frame_count=8)


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
