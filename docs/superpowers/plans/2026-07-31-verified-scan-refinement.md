# Verified Scan Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an evidence-based Verified Scan pipeline that selects trustworthy frames, keeps camera geometry stable, removes unsupported floaters, validates recognizable views, and atomically publishes only passing assets.

**Architecture:** The reconstruction worker will produce an immutable candidate plus a manifest, run CPU-only evidence scoring and three cleanup policies, and invoke GPU rendering only through an admission-controlled stage. A pure quality-gate module decides publication; rejected candidates remain diagnostic artifacts while the last-known-good verified asset stays active.

**Tech Stack:** Python 3.12, NumPy, Pillow, plyfile, gsplat/PyTorch, pytest, PostgreSQL/Neon, Cloudflare R2, systemd user services, PlayCanvas splat-transform.

## Global Constraints

- Verified assets may use only captured RGB, ARKit poses, camera calibration, LiDAR depth, and depth confidence.
- ARKit poses are frozen by default; independent per-frame pose refinement is prohibited for published verified assets.
- Unsupported regions remain holes; no generative filling is permitted in this plan.
- Generated or enhanced assets can never supply evidence coordinates, measurements, coverage, or registration.
- Failed candidates never replace the last-known-good verified asset.
- GPU-owning stages must refuse to start while another DGX GPU reservation is active.
- CPU-only tests and audits must run without importing torch or initializing CUDA.
- Existing unrelated `.env` backup files must remain untouched.

---

## File structure

- `scripts/reconstruction/frame_audit.py`: pure frame metrics, spatially balanced selection, and audit records.
- `scripts/reconstruction/evidence_score.py`: pure per-Gaussian evidence scoring and cleanup masks.
- `scripts/reconstruction/verified_manifest.py`: manifest types, checksums, serialization, and publication decision inputs.
- `scripts/reconstruction/verified_quality.py`: pure metric thresholds and last-known-good publication decision.
- `scripts/reconstruction/gpu_admission.py`: file-lock and active-process admission contract for GPU stages.
- `scripts/reconstruction/gsplat_runner.py`: dataset export orchestration and frozen-pose trainer invocation.
- `scripts/gsplat_train/train_gsplat.py`: checkpoint-capable training with frozen poses as the production default.
- `scripts/gsplat_train/clean_splat.py`: conservative, balanced, and diagnostic evidence-score cleanup.
- `scripts/gsplat_train/render_validation.py`: recorded/withheld/interpolated validation rendering.
- `scripts/reconstruction/recon_worker.py`: candidate lifecycle, validation, upload, and atomic activation.
- `site/src/db/migrations/0040_verified_reconstruction_assets.sql`: immutable asset status, kind, manifest, and parent fields.

---

### Task 1: Spatially balanced frame audit

**Files:**
- Create: `scripts/reconstruction/frame_audit.py`
- Create: `scripts/reconstruction/tests/test_frame_audit.py`
- Modify: `scripts/reconstruction/gsplat_runner.py`

**Interfaces:**
- Consumes: `list[np.ndarray]` images, camera-to-world matrices, tracking values, depth-confidence fractions, and appearance metadata.
- Produces: `audit_frames(records: list[FrameRecord], budget: int) -> FrameAuditResult` and `FrameAuditResult.selected_indices: list[int]`.

- [ ] **Step 1: Write failing metric and coverage tests**

```python
def test_audit_rejects_degraded_frames_but_preserves_route_coverage():
    records = make_records(
        x_positions=[0, 1, 2, 3, 4, 5],
        tracking=[0, 0, 1, 0, 0, 0],
        sharpness=[.8, .7, .9, .05, .8, .7],
        depth_confidence=[.5, .5, .5, .5, 0, .5],
    )
    result = audit_frames(records, budget=3)
    assert 2 not in result.selected_indices
    assert 3 not in result.selected_indices
    assert result.baseline_meters >= 4.0
    assert result.rejections[2] == ["limited-tracking"]
```

- [ ] **Step 2: Run the focused test and confirm the missing-module failure**

Run: `scripts/reconstruction/.venv/bin/python -m pytest -q scripts/reconstruction/tests/test_frame_audit.py`

Expected: FAIL because `frame_audit` does not exist.

- [ ] **Step 3: Implement pure audit records and deterministic selection**

```python
@dataclass(frozen=True)
class FrameRecord:
    index: int
    position: np.ndarray
    tracking: int
    sharpness: float
    exposure_ev: float | None
    depth_confidence_fraction: float | None

@dataclass(frozen=True)
class FrameAuditResult:
    selected_indices: list[int]
    rejections: dict[int, list[str]]
    baseline_meters: float
    normal_tracking_fraction: float
    depth_coverage_fraction: float
```

Selection must first reject hard failures, divide the camera path into `budget` equal arc-length bins, and select the highest normalized-quality candidate in each populated bin. Empty bins are filled by the best remaining candidates without duplicating indices.

- [ ] **Step 4: Replace `_export_session_frames` sharpness-only selection**

Build `FrameRecord` values from `SidecarFrame` appearance/confidence metadata and use `audit_frames`. Write the result to `frame-audit.json` inside the training dataset.

- [ ] **Step 5: Run audit and existing quality tests**

Run: `scripts/reconstruction/.venv/bin/python -m pytest -q scripts/reconstruction/tests/test_frame_audit.py scripts/reconstruction/tests/test_splat_quality_gate.py`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/reconstruction/frame_audit.py scripts/reconstruction/tests/test_frame_audit.py scripts/reconstruction/gsplat_runner.py
git commit -m "feat(reconstruction): audit frames with spatial coverage"
```

### Task 2: Frozen-pose training and recoverable checkpoints

**Files:**
- Modify: `scripts/gsplat_train/train_gsplat.py`
- Create: `scripts/gsplat_train/tests/test_checkpoint_contract.py`
- Modify: `scripts/reconstruction/gsplat_runner.py`

**Interfaces:**
- Consumes: exported dataset plus `--checkpoint-every`, `--resume`, and explicit `--pose-mode`.
- Produces: `checkpoint_latest.pt`, `training-state.json`, final PLY, and the exact camera trajectory used.

- [ ] **Step 1: Write failing CLI/default contract tests**

```python
def test_verified_defaults_freeze_poses_and_enable_checkpoints():
    args = parse_args(["--data", "/tmp/data"])
    assert args.pose_mode == "frozen"
    assert args.checkpoint_every == 1000

def test_resume_rejects_dataset_fingerprint_mismatch(tmp_path):
    write_checkpoint(tmp_path, dataset_sha256="old")
    with pytest.raises(ValueError, match="dataset fingerprint"):
        validate_resume(tmp_path, dataset_sha256="new")
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `scripts/gsplat_train/.venv/bin/python -m pytest -q scripts/gsplat_train/tests/test_checkpoint_contract.py`

Expected: FAIL because the argument parser and resume validator are not exposed.

- [ ] **Step 3: Extract `build_parser()` and add explicit pose modes**

```python
parser.add_argument("--pose-mode", choices=("frozen", "smooth-trajectory"), default="frozen")
parser.add_argument("--checkpoint-every", type=int, default=1000)
parser.add_argument("--resume")
```

Do not retain the existing independent `pose_deltas` path. `smooth-trajectory` remains rejected with a clear message until a later plan supplies its exported trajectory representation.

- [ ] **Step 4: Add atomic checkpoint writes**

Write `checkpoint_<iteration>.pt.tmp`, `fsync`, rename to `.pt`, then atomically update `checkpoint_latest.pt`. Store optimizer, scheduler, Gaussian parameters, strategy state, iteration, RNG state, dataset SHA-256, and frozen poses checksum.

- [ ] **Step 5: Add a CPU-only checkpoint round-trip test**

Use small tensor dictionaries on CPU; assert iteration, fingerprint, and RNG state round-trip without CUDA initialization.

- [ ] **Step 6: Make `gsplat_runner.py` invoke verified defaults**

Pass `--pose-mode frozen --checkpoint-every 1000`. Copy the final `poses.npy`, audit JSON, and `training-state.json` into the candidate manifest staging directory.

- [ ] **Step 7: Run focused tests**

Run: `scripts/gsplat_train/.venv/bin/python -m pytest -q scripts/gsplat_train/tests/test_checkpoint_contract.py`

Expected: PASS with no GPU allocation.

- [ ] **Step 8: Commit**

```bash
git add scripts/gsplat_train/train_gsplat.py scripts/gsplat_train/tests/test_checkpoint_contract.py scripts/reconstruction/gsplat_runner.py
git commit -m "feat(reconstruction): checkpoint frozen-pose training"
```

### Task 3: Multi-view Gaussian evidence scoring

**Files:**
- Create: `scripts/reconstruction/evidence_score.py`
- Create: `scripts/reconstruction/tests/test_evidence_score.py`

**Interfaces:**
- Consumes: Gaussian position/scale/opacity arrays, camera poses/intrinsics, confidence-weighted LiDAR points, and optional per-view visibility observations.
- Produces: `score_gaussians(inputs: EvidenceInputs) -> EvidenceResult` with normalized component scores and masks for three cleanup policies.

- [ ] **Step 1: Write synthetic facade and floater tests**

```python
def test_balanced_policy_keeps_multiview_facade_and_removes_isolated_floater():
    result = score_gaussians(two_view_facade_with_one_floater())
    assert result.balanced_mask.tolist() == [True, True, True, False]
    assert result.components["view_support"].tolist() == [1.0, 1.0, 1.0, 0.0]

def test_rgb_supported_surface_survives_beyond_lidar_range():
    result = score_gaussians(rgb_facade_without_nearby_lidar())
    assert result.balanced_mask.all()
```

- [ ] **Step 2: Run tests and verify failure**

Run: `scripts/reconstruction/.venv/bin/python -m pytest -q scripts/reconstruction/tests/test_evidence_score.py`

Expected: FAIL because `evidence_score` does not exist.

- [ ] **Step 3: Implement component scores**

```python
@dataclass(frozen=True)
class EvidenceResult:
    total: np.ndarray
    components: dict[str, np.ndarray]
    conservative_mask: np.ndarray
    balanced_mask: np.ndarray
    diagnostic_mask: np.ndarray
```

Normalize opacity, scale, anisotropy, view count, view-angle diversity, reprojection consistency, LiDAR proximity, component size, and corridor distance to `[0, 1]`. A Gaussian beyond LiDAR range remains eligible when at least two sufficiently separated views support it with consistent color.

- [ ] **Step 4: Enforce cleanup ordering invariants**

Add property tests asserting `diagnostic <= balanced <= conservative` for every Gaussian and deterministic output under input permutation.

- [ ] **Step 5: Run evidence tests**

Run: `scripts/reconstruction/.venv/bin/python -m pytest -q scripts/reconstruction/tests/test_evidence_score.py`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/reconstruction/evidence_score.py scripts/reconstruction/tests/test_evidence_score.py
git commit -m "feat(reconstruction): score multiview splat evidence"
```

### Task 4: Three immutable cleanup candidates

**Files:**
- Modify: `scripts/gsplat_train/clean_splat.py`
- Create: `scripts/gsplat_train/tests/test_cleanup_variants.py`
- Modify: `scripts/reconstruction/gsplat_runner.py`

**Interfaces:**
- Consumes: standard PLY, `EvidenceResult`, points, and poses.
- Produces: `splat_conservative.ply`, `splat_balanced.ply`, `splat_diagnostic.ply`, and `cleanup-report.json`.

- [ ] **Step 1: Write failing variant-output tests**

```python
def test_variants_are_nested_and_source_is_unchanged(tmp_path):
    report = clean_variants(source, evidence, tmp_path)
    assert report.counts["diagnostic"] <= report.counts["balanced"] <= report.counts["conservative"]
    assert sha256(source) == original_sha256
```

- [ ] **Step 2: Run and confirm failure**

Run: `scripts/gsplat_train/.venv/bin/python -m pytest -q scripts/gsplat_train/tests/test_cleanup_variants.py`

Expected: FAIL because `clean_variants` does not exist.

- [ ] **Step 3: Refactor cleanup around named policies**

Keep the existing `clean(...)` compatibility wrapper, but make it call `clean_variants(...)` and return the balanced result. Never overwrite the raw source PLY.

- [ ] **Step 4: Emit a complete cleanup report**

Include source checksum, policy thresholds, source/output counts, component-score histograms, removed-reason counts, and output checksums.

- [ ] **Step 5: Stage all variants but mark only balanced publishable**

Update `gsplat_runner.py` to return a `VerifiedCandidate` structure containing raw, three cleanup paths, poses, frame audit, and training state rather than returning anonymous PLY bytes.

- [ ] **Step 6: Run cleanup tests**

Run: `scripts/gsplat_train/.venv/bin/python -m pytest -q scripts/gsplat_train/tests/test_cleanup_variants.py scripts/gsplat_train/tests/test_human_view.py`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add scripts/gsplat_train/clean_splat.py scripts/gsplat_train/tests/test_cleanup_variants.py scripts/reconstruction/gsplat_runner.py
git commit -m "feat(reconstruction): emit verified cleanup variants"
```

### Task 5: Validation manifest and pure publication gate

**Files:**
- Create: `scripts/reconstruction/verified_manifest.py`
- Create: `scripts/reconstruction/verified_quality.py`
- Create: `scripts/reconstruction/tests/test_verified_manifest.py`
- Create: `scripts/reconstruction/tests/test_verified_quality.py`

**Interfaces:**
- Produces: `VerifiedManifest`, `QualityMetrics`, and `publication_decision(metrics, thresholds) -> PublicationDecision`.
- Consumed by: Tasks 6 and 7.

- [ ] **Step 1: Write failing manifest and gate tests**

```python
def test_failed_candidate_cannot_activate():
    decision = publication_decision(metrics(floater_ratio=.22), thresholds(max_floater_ratio=.10))
    assert decision.publish is False
    assert decision.reasons == ["floater-ratio"]

def test_manifest_checksum_covers_asset_and_generated_json(tmp_path):
    manifest = build_manifest(candidate_fixture(tmp_path))
    assert manifest.asset_sha256 == sha256(manifest.asset_path)
    assert json.loads(manifest.to_json())["kind"] == "verified"
```

- [ ] **Step 2: Run and verify missing-module failures**

Run: `scripts/reconstruction/.venv/bin/python -m pytest -q scripts/reconstruction/tests/test_verified_manifest.py scripts/reconstruction/tests/test_verified_quality.py`

Expected: FAIL.

- [ ] **Step 3: Implement immutable manifest serialization**

Manifest fields must match the approved design: IDs, sources, build/capture times, audit statistics, recipe revision, training/checkpoint values, cleanup report, metric set, review state, decision, and checksums.

- [ ] **Step 4: Implement explicit threshold decisions**

The decision function must return every failing reason, reject missing metrics, and never infer success from a trainer exit code.

- [ ] **Step 5: Run pure tests**

Run: `scripts/reconstruction/.venv/bin/python -m pytest -q scripts/reconstruction/tests/test_verified_manifest.py scripts/reconstruction/tests/test_verified_quality.py`

Expected: PASS without torch imports.

- [ ] **Step 6: Commit**

```bash
git add scripts/reconstruction/verified_manifest.py scripts/reconstruction/verified_quality.py scripts/reconstruction/tests/test_verified_manifest.py scripts/reconstruction/tests/test_verified_quality.py
git commit -m "feat(reconstruction): define verified quality gate"
```

### Task 6: Recorded, withheld, and interpolated validation views

**Files:**
- Create: `scripts/gsplat_train/render_validation.py`
- Create: `scripts/gsplat_train/tests/test_validation_views.py`
- Modify: `scripts/gsplat_train/render_ply.py`

**Interfaces:**
- Consumes: balanced candidate, dataset, deterministic validation-view specification, and frame semantic tags from the scan manifest.
- Produces: rendered PNGs, `validation-metrics.json`, `validation-contact-sheet.jpg`, and a non-GPU-testable `build_view_spec(...)` function.

- [ ] **Step 1: Write failing deterministic view-selection tests**

```python
def test_view_spec_separates_training_withheld_and_interpolated_poses():
    spec = build_view_spec(linear_camera_path(20), seed=7)
    assert set(spec.recorded).isdisjoint(spec.withheld)
    assert len(spec.interpolated) > 0
    assert all(0.0 < view.alpha < 1.0 for view in spec.interpolated)
```

- [ ] **Step 2: Run CPU-only test and verify failure**

Run: `scripts/gsplat_train/.venv/bin/python -m pytest -q scripts/gsplat_train/tests/test_validation_views.py`

Expected: FAIL because `render_validation` does not exist.

- [ ] **Step 3: Implement deterministic view specifications and image metrics**

Use recorded indices across path arc length, hold out every fifth eligible view, and interpolate only adjacent poses with translation below 1.5 m and rotation below 20 degrees. Select deterministic standardized presets for sidewalk, facade, vegetation, vehicle, and ground-facing frames when those semantic tags exist; record an explicit `not_observed` result when a category is absent rather than inventing a view. Implement PSNR and SSIM comparisons for captured views plus frame-to-frame color/depth flicker metrics for interpolated views.

- [ ] **Step 4: Add the GPU renderer behind `main()` only**

Module import and view-spec tests must not import torch. `main()` imports torch/gsplat after GPU admission succeeds.

- [ ] **Step 5: Produce a contact sheet with explicit labels**

Each row shows ground truth, balanced render, and absolute-error heatmap with frame ID and metric values.

- [ ] **Step 6: Run CPU-only tests**

Run: `scripts/gsplat_train/.venv/bin/python -m pytest -q scripts/gsplat_train/tests/test_validation_views.py`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add scripts/gsplat_train/render_validation.py scripts/gsplat_train/render_ply.py scripts/gsplat_train/tests/test_validation_views.py
git commit -m "feat(reconstruction): validate recorded and nearby views"
```

### Task 7: DGX GPU admission and reservation

**Files:**
- Create: `scripts/reconstruction/gpu_admission.py`
- Create: `scripts/reconstruction/tests/test_gpu_admission.py`
- Modify: `scripts/reconstruction/recon_worker.py`

**Interfaces:**
- Produces: `GpuReservation.acquire(owner: str, timeout_seconds: int = 0)` context manager.
- Consumed by: trainer and validation subprocess dispatch.

- [ ] **Step 1: Write failing lock and ownership tests**

```python
def test_second_gpu_owner_is_refused(tmp_path):
    first = GpuReservation(tmp_path / "gpu.lock")
    second = GpuReservation(tmp_path / "gpu.lock")
    with first.acquire("reconstruction"):
        with pytest.raises(GpuBusy, match="reconstruction"):
            with second.acquire("enhancement"):
                pass
```

- [ ] **Step 2: Run and verify failure**

Run: `scripts/reconstruction/.venv/bin/python -m pytest -q scripts/reconstruction/tests/test_gpu_admission.py`

Expected: FAIL because `gpu_admission` does not exist.

- [ ] **Step 3: Implement an `flock` reservation with metadata**

Write owner, PID, start time, command, and job ID into the locked file. A contender reports the current owner and exits without starting CUDA. Releasing the context closes the descriptor and clears metadata only when still owned by the same PID.

- [ ] **Step 4: Defer busy jobs without spending an attempt**

Wrap only GPU-owning subprocesses in the reservation. Translate `GpuBusy` to `defer_job`, preserving existing scrub-gate semantics and avoiding a retry storm.

- [ ] **Step 5: Run admission and worker tests**

Run: `scripts/reconstruction/.venv/bin/python -m pytest -q scripts/reconstruction/tests/test_gpu_admission.py scripts/reconstruction/tests/test_recon_worker.py`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/reconstruction/gpu_admission.py scripts/reconstruction/tests/test_gpu_admission.py scripts/reconstruction/recon_worker.py
git commit -m "feat(reconstruction): reserve DGX GPU jobs"
```

### Task 8: Immutable candidate records and atomic activation

**Files:**
- Create: `site/src/db/migrations/0040_verified_reconstruction_assets.sql`
- Modify: `scripts/reconstruction/recon_worker.py`
- Modify: `scripts/reconstruction/fuse_cell.py`
- Modify: `scripts/reconstruction/tests/test_recon_worker.py`
- Modify: `scripts/reconstruction/tests/test_fuse_cell.py`

**Interfaces:**
- Consumes: `VerifiedManifest`, balanced candidate, validation outputs, and `PublicationDecision`.
- Produces: immutable candidate rows and exactly one active verified asset per cell.

- [ ] **Step 1: Add failing database lifecycle tests**

```python
def test_rejected_candidate_does_not_replace_active_verified_asset(conn):
    active = insert_verified(conn, status="published", active=True, version=3)
    rejected = insert_candidate(conn, status="rejected", version=4)
    activate_candidate(conn, rejected.id)
    assert current_verified(conn).id == active.id
```

- [ ] **Step 2: Run worker tests and confirm schema failure**

Run: `scripts/reconstruction/.venv/bin/python -m pytest -q scripts/reconstruction/tests/test_recon_worker.py`

Expected: FAIL because candidate lifecycle columns do not exist in the test schema.

- [ ] **Step 3: Add migration 0040**

```sql
ALTER TABLE reconstruction_assets
  ADD COLUMN asset_kind varchar(20) NOT NULL DEFAULT 'legacy',
  ADD COLUMN processing_status varchar(20) NOT NULL DEFAULT 'published',
  ADD COLUMN review_status varchar(20) NOT NULL DEFAULT 'not_required',
  ADD COLUMN reviewed_at timestamptz,
  ADD COLUMN reviewed_by varchar(128),
  ADD COLUMN parent_asset_id bigint REFERENCES reconstruction_assets(id),
  ADD COLUMN manifest jsonb,
  ADD COLUMN asset_sha256 varchar(64),
  ADD COLUMN rejected_at timestamptz,
  ADD COLUMN rejection_reasons jsonb;

CREATE UNIQUE INDEX reconstruction_assets_one_active_verified_cell
  ON reconstruction_assets(location_cell_id)
  WHERE asset_kind='verified' AND processing_status='published' AND activated_at IS NOT NULL;
```

- [ ] **Step 4: Stage uploads under immutable candidate keys**

Use `reconstructed/{cell}/{version}/verified-candidate/...`. Upload PLY, web asset, manifest, audit, cleanup report, validation metrics, contact sheet, and checksums before opening the activation transaction.

- [ ] **Step 5: Require pilot review and activate only a passing balanced candidate**

For verified candidates, initialize `review_status='pending'`. Require both an automated passing decision and `review_status='approved'` with `reviewed_at` and `reviewed_by` populated. Inside one transaction, mark the previous verified row inactive, mark the candidate published/active, and update session reconstruction timestamps. Automated or human rejection records reasons and leaves the previous row unchanged. Add tests proving that pending, rejected, and incompletely attributed reviews cannot activate a candidate.

- [ ] **Step 6: Run lifecycle and fusion tests**

Run: `scripts/reconstruction/.venv/bin/python -m pytest -q scripts/reconstruction/tests/test_recon_worker.py scripts/reconstruction/tests/test_fuse_cell.py`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add site/src/db/migrations/0040_verified_reconstruction_assets.sql scripts/reconstruction/recon_worker.py scripts/reconstruction/fuse_cell.py scripts/reconstruction/tests/test_recon_worker.py scripts/reconstruction/tests/test_fuse_cell.py
git commit -m "feat(reconstruction): activate verified assets atomically"
```

### Task 9: End-to-end CPU verification and operator runbook

**Files:**
- Modify: `scripts/reconstruction/README.md`
- Create: `docs/governance/verified-reconstruction-operations.md`
- Create: `scripts/reconstruction/tests/test_verified_pipeline_contract.py`

**Interfaces:**
- Validates all Phase 1 interfaces without launching CUDA.
- Documents the separately approved GPU validation command for a later reserved window.

- [ ] **Step 1: Write a failing orchestration contract test**

Use fakes for training, validation, R2, and PostgreSQL. Assert state progression `queued -> auditing -> training -> cleaning -> validating -> published`, and assert a failed validation ends at `rejected` while retaining the previous active asset.

- [ ] **Step 2: Run the contract test and verify failure**

Run: `scripts/reconstruction/.venv/bin/python -m pytest -q scripts/reconstruction/tests/test_verified_pipeline_contract.py`

Expected: FAIL until Tasks 1–8 are integrated.

- [ ] **Step 3: Document operations and recovery**

Document environment variables, GPU reservation inspection, checkpoint resume, candidate artifact layout, rejection diagnosis, manual pilot approval, rollback to last-known-good, and the rule that enhanced assets never enter this pipeline.

- [ ] **Step 4: Run the full CPU reconstruction suite**

Run: `scripts/reconstruction/.venv/bin/python -m pytest -q scripts/reconstruction/tests scripts/gsplat_train/tests`

Expected: all tests pass with the existing documented xfail only; `nvidia-smi` must show no test-created CUDA process.

- [ ] **Step 5: Run static checks**

Run: `git diff --check && scripts/reconstruction/.venv/bin/python -m compileall -q scripts/reconstruction scripts/gsplat_train`

Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add scripts/reconstruction/README.md docs/governance/verified-reconstruction-operations.md scripts/reconstruction/tests/test_verified_pipeline_contract.py
git commit -m "docs(reconstruction): operate verified scan pipeline"
```

## Deferred plans

The following approved subsystems intentionally remain separate:

1. **World Twin reconstruction views:** Verified Scan default tab, Enhanced View disclosure, Compare mode, synchronized cameras, and missing/rejected states.
2. **Governed generative enhancement:** weak-region masks, controlled trajectory generation, temporal consistency, separate distillation, generated-region visualization, and enhanced publication gate.

Neither deferred subsystem may weaken the Verified Scan interfaces or publication rules defined here.
