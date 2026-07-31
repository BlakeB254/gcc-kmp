"""recon_worker queue semantics, exercised against the LOCAL scratch
Postgres (data-postgres, :5432) -- SKIP LOCKED / lease-expiry behavior is
Postgres-generic, so there's no reason to hit Neon from tests. Skipped
entirely when the local container isn't reachable."""
import hashlib
import json
import os
from pathlib import Path
import sys
import types
from types import SimpleNamespace

import numpy as np
import pytest

psycopg = pytest.importorskip("psycopg")

TEST_DSN_ADMIN = os.environ.get(
    "RECON_TEST_ADMIN_DSN",
    "postgresql://cdx_admin:cdx_secure_2024@localhost:5432/cdx_platform",
)
TEST_DB = "gcc_recon_worker_test"


def _admin_conn():
    try:
        return psycopg.connect(TEST_DSN_ADMIN, autocommit=True, connect_timeout=3)
    except Exception:  # pragma: no cover - environment-dependent
        pytest.skip("local scratch Postgres not reachable")


SCHEMA = """
CREATE TABLE reconstruction_jobs (
  id serial PRIMARY KEY,
  session_id varchar(64) NOT NULL UNIQUE,
  location_cell_id varchar(32) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'queued',
  attempts integer NOT NULL DEFAULT 0,
  leased_until timestamptz,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  splat_skip_reason varchar(32)
);
CREATE TABLE scan_sessions (
  id serial PRIMARY KEY,
  session_id varchar(64) NOT NULL UNIQUE,
  location_cell_id varchar(32),
  reconstruction_version integer,
  reconstructed_at timestamptz
);
CREATE TABLE reconstruction_assets (
  id serial PRIMARY KEY,
  location_cell_id varchar(32) NOT NULL,
  asset_type varchar(20) NOT NULL,
  format varchar(10) NOT NULL,
  r2_key varchar(500) NOT NULL UNIQUE,
  version integer NOT NULL,
  source_scan_coverage_event_ids jsonb NOT NULL,
  activated_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE capture_chunks (
  id serial PRIMARY KEY,
  session_id varchar(64) NOT NULL,
  location_cell_id varchar(32) NOT NULL,
  artifact_type varchar(24) NOT NULL,
  r2_key varchar(500) NOT NULL UNIQUE,
  format varchar(16)
);
"""

VERIFIED_ASSET_MIGRATION = (
    Path(__file__).parents[3]
    / "site/src/db/migrations/0040_verified_reconstruction_assets.sql"
)


@pytest.fixture()
def db():
    admin = _admin_conn()
    admin.execute(f'DROP DATABASE IF EXISTS {TEST_DB} (FORCE)')
    admin.execute(f'CREATE DATABASE {TEST_DB}')
    dsn = TEST_DSN_ADMIN.rsplit("/", 1)[0] + f"/{TEST_DB}"
    conn = psycopg.connect(dsn)
    with conn.cursor() as cur:
        cur.execute(SCHEMA)
        cur.execute(VERIFIED_ASSET_MIGRATION.read_text())
    conn.commit()
    yield dsn, conn
    conn.close()
    admin.execute(f'DROP DATABASE IF EXISTS {TEST_DB} (FORCE)')
    admin.close()


def _enqueue(conn, session_id: str, cell: str):
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO reconstruction_jobs (session_id, location_cell_id) VALUES (%s, %s)",
            (session_id, cell),
        )
        cur.execute(
            "INSERT INTO scan_sessions (session_id, location_cell_id) VALUES (%s, %s) ON CONFLICT DO NOTHING",
            (session_id, cell),
        )
    conn.commit()


def test_claim_leases_oldest_and_skips_locked(db):
    import recon_worker

    dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")
    _enqueue(conn, "sess-2", "3:4")

    with psycopg.connect(dsn) as c1, psycopg.connect(dsn) as c2:
        with c1.transaction():
            job1 = recon_worker.claim_job(c1)
            assert job1 is not None and job1.session_id == "sess-1"
            assert job1.attempts == 1
            # While c1's claim is uncommitted, a second worker must skip
            # sess-1's row (it's locked) and take sess-2.
            with c2.transaction():
                job2 = recon_worker.claim_job(c2)
                assert job2 is not None and job2.session_id == "sess-2"

    with conn.cursor() as cur:
        cur.execute("SELECT status, leased_until FROM reconstruction_jobs ORDER BY id")
        rows = cur.fetchall()
    assert all(status == "leased" and leased_until is not None for status, leased_until in rows)


def test_expired_lease_is_reclaimable(db):
    import recon_worker

    dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE reconstruction_jobs SET status='leased', attempts=1, leased_until=now() - interval '1 minute'"
        )
    conn.commit()

    with conn.transaction():
        job = recon_worker.claim_job(conn)
    assert job is not None and job.session_id == "sess-1"
    assert job.attempts == 2  # original lease counted one attempt


def test_fail_job_backs_off_then_fails_at_max_attempts(db):
    import recon_worker

    dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")

    with conn.transaction():
        job = recon_worker.claim_job(conn)
    recon_worker.fail_job(conn, job, RuntimeError("boom 1"))
    with conn.cursor() as cur:
        cur.execute(
            "SELECT status, leased_until > now(), last_error FROM reconstruction_jobs WHERE id=%s",
            (job.id,),
        )
        status, lease_in_future, last_error = cur.fetchone()
    assert status == "leased"
    assert lease_in_future
    assert "boom 1" in last_error
    # The same --drain process cannot immediately claim the deterministic
    # failure again.
    with conn.transaction():
        assert recon_worker.claim_job(conn) is None

    # Two later timer ticks (simulated by expiring the backoff lease) hit
    # MAX_ATTEMPTS (3) -> failed for good.
    for n in (2, 3):
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE reconstruction_jobs SET leased_until=now() - interval '1 minute'"
            )
        conn.commit()
        with conn.transaction():
            job = recon_worker.claim_job(conn)
        assert job is not None and job.attempts == n
        recon_worker.fail_job(conn, job, RuntimeError(f"boom {n}"))

    with conn.cursor() as cur:
        cur.execute("SELECT status FROM reconstruction_jobs")
        (status,) = cur.fetchone()
    assert status == "failed"
    with conn.transaction():
        assert recon_worker.claim_job(conn) is None


def test_complete_cell_records_assets_stamps_sessions_closes_sibling_jobs(db):
    import recon_worker
    from fuse_cell import FusionResult, ProducedAsset

    dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")
    _enqueue(conn, "sess-2", "1:2")   # sibling job, same cell
    _enqueue(conn, "sess-3", "9:9")   # unrelated cell -- must stay queued

    with conn.transaction():
        job = recon_worker.claim_job(conn)
    assert job.location_cell_id == "1:2"

    result = FusionResult(
        accepted_session_ids=["sess-1", "sess-2"],
        rejected_session_ids=[],
        merged_points=np.zeros((0, 3)),
        merged_colors=np.zeros((0, 3)),
        activated=False,
        splat_skip_reason="too-few-frames",
    )
    assets = [
        ProducedAsset("pointcloud", "ply", "reconstructed/1:2/1/pointcloud.ply", 1, None),
        ProducedAsset("splat", "ply", "reconstructed/1:2/1/splat.ply", 1, None),
    ]
    recon_worker.complete_cell(conn, "1:2", result, assets)
    # Idempotency: recording the same assets twice must not duplicate rows.
    recon_worker.complete_cell(conn, "1:2", result, assets)

    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM reconstruction_assets")
        assert cur.fetchone()[0] == 2
        cur.execute("SELECT activated_at FROM reconstruction_assets LIMIT 1")
        assert cur.fetchone()[0] is None  # not activated -> no activated_at
        cur.execute(
            "SELECT source_scan_coverage_event_ids->'sessions' FROM reconstruction_assets LIMIT 1"
        )
        assert cur.fetchone()[0] == ["sess-1", "sess-2"]
        cur.execute(
            "SELECT session_id, reconstruction_version, reconstructed_at FROM scan_sessions ORDER BY session_id"
        )
        rows = cur.fetchall()
    by_id = {r[0]: r for r in rows}
    assert by_id["sess-1"][1] == 1 and by_id["sess-1"][2] is not None
    assert by_id["sess-2"][1] == 1 and by_id["sess-2"][2] is not None
    assert by_id["sess-3"][1] is None

    with conn.cursor() as cur:
        cur.execute("SELECT session_id, status FROM reconstruction_jobs ORDER BY session_id")
        statuses = dict(cur.fetchall())
    assert statuses["sess-1"] == "done"
    assert statuses["sess-2"] == "done"   # sibling closed by the same run
    assert statuses["sess-3"] == "queued"

    with conn.cursor() as cur:
        cur.execute("SELECT session_id, splat_skip_reason FROM reconstruction_jobs ORDER BY session_id")
        skip_reasons = dict(cur.fetchall())
    assert skip_reasons["sess-1"] == "too-few-frames"
    assert skip_reasons["sess-2"] == "too-few-frames"  # sibling job, same cell/run
    assert skip_reasons["sess-3"] is None  # untouched job for an unrelated cell


# --- Verified candidate lifecycle: immutable records + atomic activation ---

def _insert_verified_asset(
    conn,
    *,
    cell: str = "1:2",
    version: int,
    processing_status: str,
    review_status: str = "pending",
    active: bool = False,
    reviewed: bool = False,
    decision_publish: bool = True,
    source_session_ids: list[str] | None = None,
):
    with conn.cursor() as cur:
        cur.execute(
            """INSERT INTO reconstruction_assets
                 (location_cell_id, asset_type, format, r2_key, version,
                  source_scan_coverage_event_ids, activated_at, asset_kind,
                  processing_status, review_status, reviewed_at, reviewed_by,
                  manifest, asset_sha256)
               VALUES (%s, 'splat', 'ply', %s, %s, %s::jsonb,
                       CASE WHEN %s THEN now() END, 'verified', %s, %s,
                       CASE WHEN %s THEN now() END,
                       CASE WHEN %s THEN 'pilot@example.test' END,
                       %s::jsonb, %s)
               RETURNING id""",
            (
                cell,
                f"reconstructed/{cell}/{version}/verified-candidate/verified.ply",
                version,
                json.dumps(source_session_ids or []),
                active,
                processing_status,
                review_status,
                reviewed,
                reviewed,
                '{"decision":{"publish":%s,"reasons":[]}}'
                % str(decision_publish).lower(),
                f"{'a' if decision_publish else 'b'}" * 64,
            ),
        )
        asset_id = cur.fetchone()[0]
    conn.commit()
    return asset_id


def _active_verified_id(conn, cell: str = "1:2"):
    with conn.cursor() as cur:
        cur.execute(
            """SELECT id FROM reconstruction_assets
               WHERE location_cell_id=%s AND asset_kind='verified'
                 AND processing_status='published' AND activated_at IS NOT NULL""",
            (cell,),
        )
        row = cur.fetchone()
    return row[0] if row else None


def test_rejected_candidate_does_not_replace_active_verified_asset(db):
    """Catches an automated rejection deactivating the last-known-good row."""
    import recon_worker

    _dsn, conn = db
    active = _insert_verified_asset(
        conn, version=3, processing_status="published", active=True
    )
    rejected = _insert_verified_asset(
        conn,
        version=4,
        processing_status="candidate",
        decision_publish=False,
    )

    assert recon_worker.activate_verified_candidate(conn, rejected) is False
    assert _active_verified_id(conn) == active
    with conn.cursor() as cur:
        cur.execute(
            "SELECT processing_status, rejected_at, rejection_reasons FROM reconstruction_assets WHERE id=%s",
            (rejected,),
        )
        status, rejected_at, reasons = cur.fetchone()
    assert status == "rejected"
    assert rejected_at is not None
    assert reasons == ["automated-quality-rejected"]


def test_recorded_verified_candidate_is_pending_and_content_immutable(db):
    """Catches a retry mutating candidate provenance or bypassing pilot review."""
    import recon_worker
    from fuse_cell import StagedVerifiedCandidate
    from verified_quality import PublicationDecision

    _dsn, conn = db
    manifest_document = {
        "kind": "verified",
        "cellIds": ["1:2"],
        "decision": {"publish": True, "reasons": []},
        "checksums": {"assetSha256": "a" * 64},
    }
    manifest = SimpleNamespace(
        to_dict=lambda: manifest_document,
        asset_sha256="a" * 64,
    )
    prefix = "reconstructed/1:2/4/verified-candidate/"
    staged = StagedVerifiedCandidate(
        cell_id="1:2",
        version=4,
        r2_key=prefix + "verified.ply",
        asset_sha256="a" * 64,
        artifact_keys=tuple(
            prefix + name
            for name in (
                "verified.ply",
                "verified.spz",
                "manifest.json",
                "frame-audit.json",
                "cleanup-report.json",
                "validation-metrics.json",
                "validation-contact-sheet.jpg",
                "checksums.json",
            )
        ),
    )
    decision = PublicationDecision(publish=True, reasons=[])

    first = recon_worker.record_verified_candidate(
        conn,
        staged,
        manifest,
        decision,
        source_session_ids=["sess-1"],
    )
    retry = recon_worker.record_verified_candidate(
        conn,
        staged,
        manifest,
        decision,
        source_session_ids=["sess-1"],
    )
    assert retry == first
    with conn.cursor() as cur:
        cur.execute(
            "SELECT processing_status, review_status, activated_at FROM reconstruction_assets WHERE id=%s",
            (first,),
        )
        assert cur.fetchone() == ("candidate", "pending", None)

    recon_worker.review_verified_candidate(
        conn, first, approved=True, reviewed_by="pilot@example.test"
    )
    assert recon_worker.record_verified_candidate(
        conn,
        staged,
        manifest,
        decision,
        source_session_ids=["sess-1"],
    ) == first
    with conn.cursor() as cur:
        cur.execute(
            "SELECT review_status, reviewed_by FROM reconstruction_assets WHERE id=%s",
            (first,),
        )
        assert cur.fetchone() == ("approved", "pilot@example.test")

    changed = StagedVerifiedCandidate(
        cell_id=staged.cell_id,
        version=staged.version,
        r2_key=staged.r2_key,
        asset_sha256="b" * 64,
        artifact_keys=staged.artifact_keys,
    )
    changed_document = {
        **manifest_document,
        "checksums": {"assetSha256": "b" * 64},
    }
    changed_manifest = SimpleNamespace(
        to_dict=lambda: changed_document,
        asset_sha256="b" * 64,
    )
    with pytest.raises(ValueError, match="immutable candidate row"):
        recon_worker.record_verified_candidate(
            conn,
            changed,
            changed_manifest,
            decision,
            source_session_ids=["sess-1"],
        )


def test_incomplete_upload_receipt_cannot_create_candidate_row(db):
    """Catches a partial object upload becoming reviewable in the database."""
    import recon_worker
    from fuse_cell import StagedVerifiedCandidate
    from verified_quality import PublicationDecision

    _dsn, conn = db
    r2_key = "reconstructed/1:2/4/verified-candidate/verified.ply"
    staged = StagedVerifiedCandidate(
        cell_id="1:2",
        version=4,
        r2_key=r2_key,
        asset_sha256="a" * 64,
        artifact_keys=(r2_key,),
    )
    document = {
        "kind": "verified",
        "cellIds": ["1:2"],
        "decision": {"publish": True, "reasons": []},
    }
    manifest = SimpleNamespace(to_dict=lambda: document, asset_sha256="a" * 64)

    with pytest.raises(ValueError, match="complete upload receipt"):
        recon_worker.record_verified_candidate(
            conn,
            staged,
            manifest,
            PublicationDecision(publish=True, reasons=[]),
            source_session_ids=["sess-1"],
        )
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM reconstruction_assets")
        assert cur.fetchone()[0] == 0


def test_verified_candidate_content_columns_cannot_be_mutated(db):
    """Catches direct SQL changing the bytes/provenance identity of a candidate row."""
    _dsn, conn = db
    candidate = _insert_verified_asset(
        conn,
        version=4,
        processing_status="candidate",
        source_session_ids=["sess-1"],
    )
    with pytest.raises(psycopg.errors.RaiseException, match="immutable"):
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE reconstruction_assets SET r2_key='reconstructed/1:2/4/changed.ply' WHERE id=%s",
                (candidate,),
            )
    conn.rollback()


@pytest.mark.parametrize(
    ("review_status", "reviewed"),
    [("pending", False), ("approved", False), ("rejected", True)],
)
def test_review_without_attributed_approval_cannot_activate(
    db, review_status, reviewed
):
    """Catches pending, rejected, or unattributed pilot review bypassing the gate."""
    import recon_worker

    _dsn, conn = db
    active = _insert_verified_asset(
        conn, version=3, processing_status="published", active=True
    )
    candidate = _insert_verified_asset(
        conn,
        version=4,
        processing_status="candidate",
        review_status=review_status,
        reviewed=reviewed,
    )

    assert recon_worker.activate_verified_candidate(conn, candidate) is False
    assert _active_verified_id(conn) == active


def test_attributed_approved_candidate_swaps_atomically_and_stamps_sessions(db):
    """Catches publishing the new row without superseding the old row and session version."""
    import recon_worker

    _dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")
    active = _insert_verified_asset(
        conn, version=3, processing_status="published", active=True
    )
    candidate = _insert_verified_asset(
        conn,
        version=4,
        processing_status="candidate",
        review_status="approved",
        reviewed=True,
    )

    assert recon_worker.activate_verified_candidate(
        conn, candidate, source_session_ids=["sess-1"]
    ) is True
    assert _active_verified_id(conn) == candidate
    with conn.cursor() as cur:
        cur.execute(
            "SELECT processing_status, activated_at FROM reconstruction_assets WHERE id=%s",
            (active,),
        )
        old_status, old_activated_at = cur.fetchone()
        cur.execute(
            "SELECT reconstruction_version, reconstructed_at FROM scan_sessions WHERE session_id='sess-1'"
        )
        version, reconstructed_at = cur.fetchone()
    assert old_status == "superseded"
    assert old_activated_at is not None
    assert version == 4
    assert reconstructed_at is not None


def test_activation_failure_rolls_back_and_preserves_last_good(db):
    """Catches a mid-swap database failure leaving the cell with no active verified row."""
    import recon_worker

    _dsn, conn = db
    active = _insert_verified_asset(
        conn, version=3, processing_status="published", active=True
    )
    candidate = _insert_verified_asset(
        conn,
        version=4,
        processing_status="candidate",
        review_status="approved",
        reviewed=True,
    )
    with conn.cursor() as cur:
        cur.execute(
            f"""CREATE FUNCTION fail_candidate_activation() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'activation failed'; END $$ LANGUAGE plpgsql;
                CREATE TRIGGER fail_candidate_activation
                  BEFORE UPDATE ON reconstruction_assets
                  FOR EACH ROW WHEN (NEW.id = {candidate} AND NEW.processing_status = 'published')
                  EXECUTE FUNCTION fail_candidate_activation();"""
        )
    conn.commit()

    with pytest.raises(psycopg.DatabaseError, match="activation failed"):
        recon_worker.activate_verified_candidate(conn, candidate)

    assert _active_verified_id(conn) == active


def test_verified_asset_migration_is_idempotent_and_enforces_one_active_per_cell(db):
    """Catches a replayed migration failing or the active-row invariant being unenforced."""
    _dsn, conn = db
    migration = VERIFIED_ASSET_MIGRATION.read_text()
    with conn.cursor() as cur:
        cur.execute(migration)
    conn.commit()
    _insert_verified_asset(conn, version=1, processing_status="published", active=True)
    with pytest.raises(psycopg.errors.UniqueViolation):
        _insert_verified_asset(conn, version=2, processing_status="published", active=True)
    conn.rollback()


def _complete_staged_receipt(cell: str, version: int, sha256: str):
    from fuse_cell import StagedVerifiedCandidate

    prefix = f"reconstructed/{cell}/{version}/verified-candidate/"
    names = (
        "verified.ply",
        "verified.spz",
        "manifest.json",
        "frame-audit.json",
        "cleanup-report.json",
        "validation-metrics.json",
        "validation-contact-sheet.jpg",
        "checksums.json",
    )
    return StagedVerifiedCandidate(
        cell_id=cell,
        version=version,
        r2_key=prefix + "verified.ply",
        asset_sha256=sha256,
        artifact_keys=tuple(prefix + name for name in names),
    )


class _PublicationCandidateFixture:
    """Durable artifact fixture consumed by the real publication producer."""

    publishable_policy = "balanced"
    generation_id = "generation-4"

    def __init__(self, root: Path, *, include_depth_error: bool = True):
        from clean_splat import clean_variants
        from evidence_score import EvidenceResult
        from plyfile import PlyData, PlyElement
        from render_validation import (
            snapshot_validation_comparisons,
            validation_view_spec_from_dict,
            write_validation_metrics,
        )
        from verified_evidence import ply_fingerprints

        self.raw = root / "splat_gsplat.ply"
        self.conservative = root / "splat_conservative.ply"
        self.balanced = root / "splat_balanced.ply"
        self.diagnostic = root / "splat_diagnostic.ply"
        self.cleanup_report = root / "cleanup-report.json"
        self.evidence_artifact = root / "verified-evidence.npz"
        self.poses = root / "poses.npy"
        self.frame_audit = root / "frame-audit.json"
        self.training_state = root / "training-state.json"
        self.checkpoint = root / "checkpoint_latest.pt"
        vertices = np.zeros(
            4,
            dtype=[
                ("x", "f4"),
                ("y", "f4"),
                ("z", "f4"),
                ("opacity", "f4"),
                ("scale_0", "f4"),
                ("scale_1", "f4"),
                ("scale_2", "f4"),
            ],
        )
        # All evidence-supported candidates occupy one deterministic 0.5 m
        # voxel, so balanced retains real conservative spatial coverage.
        vertices["x"] = [0.0, 0.1, 0.2, 1.0]
        vertices["opacity"] = 1.0
        for name in ("scale_0", "scale_1", "scale_2"):
            vertices[name] = np.log(0.05)
        PlyData([PlyElement.describe(vertices, "vertex")]).write(self.raw)

        def frozen(values, *, dtype=float):
            result = np.asarray(values, dtype=dtype)
            result.setflags(write=False)
            return result

        evidence = EvidenceResult(
            total=frozen([0.95, 0.85, 0.50, 0.10]),
            components={
                "lidar_proximity": frozen([1.0, 0.8, 0.6, 0.0]),
                "view_support": frozen([1.0, 1.0, 0.0, 0.0]),
                "component_size": frozen([1.0, 0.8, 0.4, 0.0]),
            },
            conservative_mask=frozen([True, True, True, False], dtype=bool),
            balanced_mask=frozen([True, True, False, False], dtype=bool),
            diagnostic_mask=frozen([True, True, False, False], dtype=bool),
            source_vertex_sha256=ply_fingerprints(self.raw)[1],
        )
        clean_variants(self.raw, evidence, root)

        self.evidence_artifact.write_bytes(b"verified evidence fixture")
        np.save(self.poses, np.repeat(np.eye(4)[None], 2, axis=0))
        self.checkpoint.write_bytes(b"checkpoint")
        self.frame_audit.write_text(
            json.dumps(
                {
                    "selectedFrames": 12,
                    "rejectedFrames": {"blur": 2},
                }
            )
        )
        self.training_state.write_text(
            json.dumps({"iterations": 30_000, "codeRevision": "fixture-revision"})
        )
        captured = []
        image = np.zeros((4, 4, 3), dtype=np.float64)
        for index in range(8):
            kind = "withheld" if index == 4 else "recorded"
            row = {
                "frame_id": f"{kind}-{index:04d}",
                "kind": kind,
                "frame_index": index,
                "ground_truth": image.copy(),
                "balanced_render": image.copy(),
            }
            if kind == "withheld":
                row.update(
                    {
                        "captured_depth": np.array([[2.0, 4.0]], dtype=np.float64),
                        "balanced_depth": np.array([[2.04, 4.08]], dtype=np.float64),
                        "depth_confidence": np.array(
                            [[2, 1]] if include_depth_error else [[0, 0]],
                            dtype=np.uint8,
                        ),
                    }
                )
            captured.append(row)
        interpolated = [
            {
                "start_index": start,
                "end_index": end,
                "alpha": 0.5,
                "color_frames": (
                    np.zeros((2, 2, 3)),
                    np.full((2, 2, 3), 0.01),
                    np.full((2, 2, 3), 0.02),
                ),
                "depth_frames": (
                    np.ones((2, 2)),
                    np.full((2, 2), 1.001),
                    np.full((2, 2), 1.002),
                ),
            }
            for start, end in ((0, 1), (6, 7))
        ]
        self.validation_metrics = root / "validation-metrics.json"
        snapshots = snapshot_validation_comparisons(
            captured,
            interpolated_comparisons=interpolated,
            workspace=root / "validation-evidence",
        )
        spec = validation_view_spec_from_dict(
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
        write_validation_metrics(
            snapshots, self.validation_metrics, view_spec=spec
        )
        self.contact_sheet = root / "validation-contact-sheet.jpg"
        self.contact_sheet.write_bytes(b"jpeg")

    @property
    def publishable(self):
        return self.balanced

    @property
    def balanced_bytes(self):
        return self.balanced.read_bytes()


def _set_passing_publication_thresholds(monkeypatch):
    values = {
        "VERIFIED_MIN_IMAGE_SIMILARITY": "0.90",
        "VERIFIED_MAX_DEPTH_ERROR": "0.10",
        "VERIFIED_MAX_TEMPORAL_FLICKER": "0.05",
        "VERIFIED_MAX_FLOATER_RATIO": "0.10",
        "VERIFIED_MIN_RETAINED_COVERAGE": "0.80",
        "VERIFIED_MAX_ASSET_SIZE_BYTES": "2000",
    }
    for name, value in values.items():
        monkeypatch.setenv(name, value)


def _verified_fixture_result(tmp_path: Path, *, include_depth_error: bool = True):
    from fuse_cell import FusionResult

    candidate = _PublicationCandidateFixture(
        tmp_path, include_depth_error=include_depth_error
    )
    result = FusionResult(accepted_session_ids=["sess-1"])
    result.verified_candidate = candidate
    result.verified_version = 4
    result.validation_metrics = candidate.validation_metrics
    result.validation_contact_sheet = candidate.contact_sheet
    return result


def test_real_artifacts_produce_canonical_metrics_manifest_and_pending_candidate(
    db, monkeypatch, tmp_path
):
    """Catches the worker retaining a typed gate without a production producer."""
    import gsplat_runner
    import recon_worker

    _dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM reconstruction_jobs WHERE session_id='sess-1'")
        job_id = cur.fetchone()[0]
    conn.commit()
    result = _verified_fixture_result(tmp_path)
    sha256 = hashlib.sha256(result.verified_candidate.balanced_bytes).hexdigest()
    staged_manifests = []

    _set_passing_publication_thresholds(monkeypatch)
    monkeypatch.setenv("REQUIRE_SCRUBBED_VIDEO", "0")
    monkeypatch.setenv("RECON_TRAIN_SPLAT", "1")
    monkeypatch.setenv("SPLAT_ENGINE", "gsplat")
    monkeypatch.setattr(gsplat_runner, "available", lambda: True)
    monkeypatch.setattr(
        recon_worker.fuse_cell_module, "fuse_cell", lambda *_a, **_k: result
    )
    monkeypatch.setattr(
        recon_worker, "compress_splat_for_web", lambda _data: (b"web", "spz")
    )
    monkeypatch.setattr(
        recon_worker.fuse_cell_module,
        "stage_verified_candidate_assets",
        lambda _cell, _version, _candidate, manifest, **_kwargs:
        staged_manifests.append(manifest)
        or _complete_staged_receipt("1:2", 4, sha256),
    )

    recon_worker.process_job(conn, recon_worker.Job(job_id, "sess-1", "1:2", 1))

    assert result.verified_metrics.to_dict() == {
        "imageSimilarity": 1.0,
        "depthError": pytest.approx(0.02),
        "temporalFlicker": pytest.approx(0.01),
        "floaterRatio": 0.0,
        "retainedCoverage": 1.0,
        "assetSizeBytes": len(result.verified_candidate.balanced_bytes),
    }
    assert result.publication_decision.to_dict() == {"publish": True, "reasons": []}
    document = staged_manifests[0].to_dict()
    assert result.verified_manifest is staged_manifests[0]
    assert document["metrics"] == result.verified_metrics.to_dict()
    assert document["auditStatistics"]["selectedFrames"] == 12
    assert document["training"]["iterations"] == 30_000
    with conn.cursor() as cur:
        cur.execute(
            "SELECT processing_status, review_status, activated_at FROM reconstruction_assets"
        )
        assert cur.fetchone() == ("candidate", "pending", None)


def test_missing_direct_metric_is_rejected_without_fabrication(
    db, monkeypatch, tmp_path
):
    """Catches depth flicker or another proxy being relabeled as depth error."""
    import gsplat_runner
    import recon_worker

    _dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM reconstruction_jobs WHERE session_id='sess-1'")
        job_id = cur.fetchone()[0]
    conn.commit()
    result = _verified_fixture_result(tmp_path, include_depth_error=False)
    sha256 = hashlib.sha256(result.verified_candidate.balanced_bytes).hexdigest()

    _set_passing_publication_thresholds(monkeypatch)
    monkeypatch.setenv("REQUIRE_SCRUBBED_VIDEO", "0")
    monkeypatch.setenv("RECON_TRAIN_SPLAT", "1")
    monkeypatch.setenv("SPLAT_ENGINE", "gsplat")
    monkeypatch.setattr(gsplat_runner, "available", lambda: True)
    monkeypatch.setattr(
        recon_worker.fuse_cell_module, "fuse_cell", lambda *_a, **_k: result
    )
    monkeypatch.setattr(
        recon_worker, "compress_splat_for_web", lambda _data: (b"web", "spz")
    )
    monkeypatch.setattr(
        recon_worker.fuse_cell_module,
        "stage_verified_candidate_assets",
        lambda *_a, **_k: _complete_staged_receipt("1:2", 4, sha256),
    )

    recon_worker.process_job(conn, recon_worker.Job(job_id, "sess-1", "1:2", 1))

    validation_document = json.loads(result.validation_metrics.read_text())
    assert validation_document["aggregates"]["depthQualifiedPixels"] == 0
    assert "meanDepthError" not in validation_document["aggregates"]
    assert "meanDepthFlicker" in validation_document["aggregates"]
    assert result.verified_metrics.depth_error is None
    assert result.publication_decision.reasons == ["depth-error-missing"]
    with conn.cursor() as cur:
        cur.execute("SELECT processing_status, rejection_reasons FROM reconstruction_assets")
        assert cur.fetchone() == ("rejected", ["depth-error-missing"])


def test_missing_threshold_configuration_fails_closed(monkeypatch, tmp_path):
    """Catches an implicit/default threshold silently authorizing publication."""
    import recon_worker

    result = _verified_fixture_result(tmp_path)
    for name in recon_worker.QUALITY_THRESHOLD_ENV:
        monkeypatch.delenv(name, raising=False)

    manifest, decision = recon_worker._verified_publication(result, "1:2")

    assert decision.publish is False
    assert decision.reasons == [
        f"quality-threshold-missing:{name}"
        for name in recon_worker.QUALITY_THRESHOLD_ENV
    ]
    assert manifest.to_dict()["decision"] == decision.to_dict()


def test_process_job_records_verified_success_pending_without_legacy_completion(
    db, monkeypatch, tmp_path
):
    """Catches a validated gsplat falling through complete_cell and becoming active."""
    import hashlib
    import gsplat_runner
    import recon_worker
    from fuse_cell import FusionResult
    from verified_quality import PublicationDecision, QualityMetrics

    _dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM reconstruction_jobs WHERE session_id='sess-1'")
        job_id = cur.fetchone()[0]
    conn.commit()
    candidate_bytes = b"verified-balanced"
    candidate = SimpleNamespace(balanced_bytes=candidate_bytes)
    decision = PublicationDecision(publish=True, reasons=[])
    quality_metrics = QualityMetrics(
        image_similarity=0.95,
        depth_error=0.02,
        temporal_flicker=0.01,
        floater_ratio=0.04,
        retained_coverage=0.92,
        asset_size_bytes=len(candidate_bytes),
    )
    sha256 = hashlib.sha256(candidate_bytes).hexdigest()
    document = {
        "kind": "verified",
        "cellIds": ["1:2"],
        "decision": decision.to_dict(),
        "metrics": quality_metrics.to_dict(),
        "checksums": {"assetSha256": sha256},
    }
    manifest = SimpleNamespace(
        to_dict=lambda: document,
        to_json=lambda: __import__("json").dumps(document),
        asset_sha256=sha256,
    )
    metrics = tmp_path / "validation-metrics.json"
    contact = tmp_path / "validation-contact-sheet.jpg"
    metrics.write_text("{}")
    contact.write_bytes(b"jpeg")
    result = FusionResult(
        accepted_session_ids=["sess-1"],
        merged_points=np.zeros((0, 3)),
        merged_colors=np.zeros((0, 3)),
    )
    result.verified_candidate = candidate
    result.verified_version = 4
    result.verified_manifest = manifest
    result.publication_decision = decision
    result.verified_metrics = quality_metrics
    result.validation_metrics = metrics
    result.validation_contact_sheet = contact
    staged_calls = []

    monkeypatch.setenv("REQUIRE_SCRUBBED_VIDEO", "0")
    monkeypatch.setenv("RECON_TRAIN_SPLAT", "1")
    monkeypatch.setenv("SPLAT_ENGINE", "gsplat")
    monkeypatch.setattr(gsplat_runner, "available", lambda: True)
    monkeypatch.setattr(recon_worker.fuse_cell_module, "fuse_cell", lambda *_a, **_k: result)
    monkeypatch.setattr(recon_worker, "compress_splat_for_web", lambda _data: (b"web", "spz"))
    monkeypatch.setattr(
        recon_worker.fuse_cell_module,
        "stage_verified_candidate_assets",
        lambda *args, **kwargs: staged_calls.append((args, kwargs))
        or _complete_staged_receipt("1:2", 4, sha256),
    )
    monkeypatch.setattr(
        recon_worker,
        "complete_cell",
        lambda *_a, **_k: pytest.fail("verified candidate used legacy completion"),
    )
    monkeypatch.setattr(
        recon_worker,
        "activate_verified_candidate",
        lambda *_a, **_k: pytest.fail("pending candidate activated before review"),
    )

    recon_worker.process_job(conn, recon_worker.Job(job_id, "sess-1", "1:2", 1))

    assert len(staged_calls) == 1
    with conn.cursor() as cur:
        cur.execute(
            """SELECT asset_kind, processing_status, review_status, activated_at
               FROM reconstruction_assets"""
        )
        assert cur.fetchone() == ("verified", "candidate", "pending", None)
        cur.execute(
            "SELECT reconstruction_version, reconstructed_at FROM scan_sessions WHERE session_id='sess-1'"
        )
        assert cur.fetchone() == (None, None)
        cur.execute("SELECT status FROM reconstruction_jobs WHERE id=%s", (job_id,))
        assert cur.fetchone()[0] == "done"


def test_process_job_invalid_precomputed_decision_is_stably_rejected(
    db, monkeypatch, tmp_path
):
    """Catches malformed precomputed output falling back to legacy publication."""
    import hashlib
    import gsplat_runner
    import recon_worker
    from fuse_cell import FusionResult

    _dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM reconstruction_jobs WHERE session_id='sess-1'")
        job_id = cur.fetchone()[0]
    conn.commit()
    candidate_bytes = b"verified-balanced"
    sha256 = hashlib.sha256(candidate_bytes).hexdigest()
    result = FusionResult(accepted_session_ids=["sess-1"])
    result.verified_candidate = SimpleNamespace(balanced_bytes=candidate_bytes)
    result.verified_version = 4
    result.verified_manifest = None
    result.publication_decision = object()
    result.validation_metrics = tmp_path / "validation-metrics.json"
    result.validation_contact_sheet = tmp_path / "validation-contact-sheet.jpg"
    result.validation_metrics.write_text("{}")
    result.validation_contact_sheet.write_bytes(b"jpeg")

    def rejected_manifest(_candidate, **kwargs):
        decision = kwargs["decision"]
        document = {
            "kind": "verified",
            "cellIds": ["1:2"],
            "decision": decision.to_dict(),
            "checksums": {"assetSha256": sha256},
        }
        return SimpleNamespace(
            to_dict=lambda: document,
            to_json=lambda: __import__("json").dumps(document),
            asset_sha256=sha256,
        )

    monkeypatch.setenv("REQUIRE_SCRUBBED_VIDEO", "0")
    monkeypatch.setenv("RECON_TRAIN_SPLAT", "1")
    monkeypatch.setenv("SPLAT_ENGINE", "gsplat")
    monkeypatch.setattr(gsplat_runner, "available", lambda: True)
    monkeypatch.setattr(recon_worker.fuse_cell_module, "fuse_cell", lambda *_a, **_k: result)
    monkeypatch.setattr(recon_worker, "build_manifest", rejected_manifest, raising=False)
    monkeypatch.setattr(recon_worker, "compress_splat_for_web", lambda _data: (b"web", "spz"))
    monkeypatch.setattr(
        recon_worker.fuse_cell_module,
        "stage_verified_candidate_assets",
        lambda *_a, **_k: _complete_staged_receipt("1:2", 4, sha256),
    )
    monkeypatch.setattr(
        recon_worker,
        "complete_cell",
        lambda *_a, **_k: pytest.fail("invalid verified result used legacy completion"),
    )

    recon_worker.process_job(conn, recon_worker.Job(job_id, "sess-1", "1:2", 1))

    with conn.cursor() as cur:
        cur.execute(
            "SELECT processing_status, rejection_reasons, activated_at FROM reconstruction_assets"
        )
        status, reasons, activated_at = cur.fetchone()
    assert status == "rejected"
    assert reasons == ["publication-decision-invalid"]
    assert activated_at is None


def test_review_activation_resume_publishes_pending_candidate(db):
    """Catches an approved candidate having no worker path to resume activation."""
    import recon_worker

    _dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")
    old = _insert_verified_asset(
        conn, version=3, processing_status="published", active=True
    )
    candidate = _insert_verified_asset(
        conn,
        version=4,
        processing_status="candidate",
        source_session_ids=["sess-1"],
    )
    recon_worker.review_verified_candidate(
        conn, candidate, approved=True, reviewed_by="pilot@example.test"
    )

    assert recon_worker.activate_reviewed_candidates(conn) == 1
    assert _active_verified_id(conn) == candidate
    with conn.cursor() as cur:
        cur.execute("SELECT processing_status FROM reconstruction_assets WHERE id=%s", (old,))
        assert cur.fetchone()[0] == "superseded"
        cur.execute(
            "SELECT reconstruction_version, reconstructed_at FROM scan_sessions WHERE session_id='sess-1'"
        )
        version, reconstructed_at = cur.fetchone()
    assert version == 4
    assert reconstructed_at is not None


def test_normal_queue_poll_activates_reviewed_candidate_without_manual_flag(db):
    """Catches --drain/--loop ignoring approvals when there is no queued job."""
    import recon_worker

    _dsn, conn = db
    candidate = _insert_verified_asset(
        conn,
        version=4,
        processing_status="candidate",
        review_status="approved",
        reviewed=True,
    )

    assert recon_worker.run_once(conn) is False
    assert _active_verified_id(conn) == candidate


def test_review_activation_scan_is_bounded_and_idempotent(db):
    """Catches one queue poll doing unbounded approval work or replaying rows."""
    import recon_worker

    _dsn, conn = db
    candidates = [
        _insert_verified_asset(
            conn,
            cell=f"cell:{index}",
            version=1,
            processing_status="candidate",
            review_status="approved",
            reviewed=True,
        )
        for index in range(3)
    ]

    assert recon_worker.activate_reviewed_candidates(conn, limit=2) == 2
    with conn.cursor() as cur:
        cur.execute(
            "SELECT count(*) FROM reconstruction_assets WHERE processing_status='published'"
        )
        assert cur.fetchone()[0] == 2
    assert recon_worker.activate_reviewed_candidates(conn, limit=2) == 1
    assert recon_worker.activate_reviewed_candidates(conn, limit=2) == 0
    assert all(
        _active_verified_id(conn, f"cell:{index}") == candidate
        for index, candidate in enumerate(candidates)
    )


def test_queue_cycle_scans_approvals_before_and_after_processing(monkeypatch):
    """Catches an approval arriving during a job waiting until another poll."""
    import recon_worker

    events = []
    job = recon_worker.Job(17, "sess-1", "1:2", 1)

    class _Connection:
        def transaction(self):
            from contextlib import nullcontext
            return nullcontext()

    monkeypatch.setattr(
        recon_worker,
        "activate_reviewed_candidates",
        lambda _conn, **_kwargs: events.append("activate") or 0,
    )
    monkeypatch.setattr(recon_worker, "claim_job", lambda _conn: job)
    monkeypatch.setattr(
        recon_worker,
        "process_job",
        lambda _conn, _job: events.append("process"),
    )

    assert recon_worker.run_once(_Connection()) is True
    assert events == ["activate", "process", "activate"]



# --- compress_splat_for_web fallback logic (subprocess mocked; no DB) ---

class _FakeProc:
    def __init__(self, returncode):
        self.returncode = returncode
        self.stderr = "boom" if returncode else ""


def test_compress_splat_prefers_spz(monkeypatch):
    import recon_worker

    def fake_run(cmd, **kwargs):
        out = cmd[-1]
        with open(out, "wb") as f:
            f.write(b"SPZ" if out.endswith(".spz") else b"PLYC")
        return _FakeProc(0)

    monkeypatch.setattr(recon_worker.subprocess, "run", fake_run)
    data, fmt = recon_worker.compress_splat_for_web(b"fake-ply")
    assert (data, fmt) == (b"SPZ", "spz")


def test_compress_splat_falls_back_to_compressed_ply(monkeypatch):
    import recon_worker

    def fake_run(cmd, **kwargs):
        out = cmd[-1]
        if out.endswith(".spz"):
            return _FakeProc(1)  # spz path broken
        with open(out, "wb") as f:
            f.write(b"PLYC")
        return _FakeProc(0)

    monkeypatch.setattr(recon_worker.subprocess, "run", fake_run)
    data, fmt = recon_worker.compress_splat_for_web(b"fake-ply")
    assert (data, fmt) == (b"PLYC", "ply")


def test_compress_splat_returns_none_when_all_paths_fail(monkeypatch):
    import recon_worker

    monkeypatch.setattr(recon_worker.subprocess, "run", lambda cmd, **k: _FakeProc(1))
    assert recon_worker.compress_splat_for_web(b"fake-ply") is None


def _add_chunk(conn, session_id: str, cell: str, r2_key: str, fmt: str = "mov"):
    with conn.cursor() as cur:
        cur.execute(
            """INSERT INTO capture_chunks (session_id, location_cell_id, artifact_type, r2_key, format)
               VALUES (%s, %s, 'video', %s, %s)""",
            (session_id, cell, r2_key, fmt),
        )
    conn.commit()


def test_unscrubbed_video_chunks_joins_by_session_not_chunk_cell(db, monkeypatch):
    """Chunk keys can live under a NEIGHBORING cell prefix (GPS drift between
    manifest and chunk upload) — the gate must find them via the session's
    cell, and must ignore non-video artifacts."""
    import recon_worker

    dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")
    _add_chunk(conn, "sess-1", "1:3", "raw/1:3/sess-1/capture/chunk-0.mov", "mov")
    _add_chunk(conn, "sess-1", "1:2", "raw/1:2/sess-1/capture/chunk-0.jsonl", "jsonl")

    monkeypatch.setattr(recon_worker.r2_store, "object_exists", lambda key: False)
    assert recon_worker.unscrubbed_video_chunks(conn, "1:2") == [
        "raw/1:3/sess-1/capture/chunk-0.mov"
    ]

    monkeypatch.setattr(recon_worker.r2_store, "object_exists", lambda key: True)
    assert recon_worker.unscrubbed_video_chunks(conn, "1:2") == []


def test_scrub_pending_defers_without_burning_attempts(db, monkeypatch):
    """A job whose chunks aren't scrubbed yet is re-leased into the future
    (invisible to claim_job) with its attempt refunded — waiting on the scrub
    timer must never walk a job to 'failed'."""
    import recon_worker

    dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")
    _add_chunk(conn, "sess-1", "1:2", "raw/1:2/sess-1/capture/chunk-0.mov")
    monkeypatch.setenv("REQUIRE_SCRUBBED_VIDEO", "1")
    monkeypatch.setenv("PREFER_SCRUBBED_VIDEO", "1")
    monkeypatch.setattr(recon_worker.r2_store, "object_exists", lambda key: False)

    assert recon_worker.run_once(conn) is True

    with conn.cursor() as cur:
        cur.execute(
            "SELECT status, attempts, leased_until > now(), last_error FROM reconstruction_jobs"
        )
        status, attempts, lease_in_future, last_error = cur.fetchone()
    assert status == "leased"
    assert attempts == 0  # the claim's increment was refunded
    assert lease_in_future
    assert "lack scrubbed renditions" in last_error

    # Deferred job is invisible to the next claim until the lease expires.
    with conn.transaction():
        assert recon_worker.claim_job(conn) is None


def test_gpu_busy_defers_without_burning_attempts(monkeypatch):
    """Changing GPU contention into failure would consume queue attempts."""
    import gpu_admission

    # This test covers queue control flow only.  Keep the heavyweight fusion
    # dependency outside it so it remains CPU-only on a scheduler host.
    if "recon_worker" not in sys.modules:
        fake_fuse_cell = types.ModuleType("fuse_cell")
        fake_fuse_cell.FusionResult = type("FusionResult", (), {})
        fake_fuse_cell.ProducedAsset = type("ProducedAsset", (), {})
        fake_fuse_cell.StagedVerifiedCandidate = type("StagedVerifiedCandidate", (), {})
        fake_fuse_cell.fuse_cell = lambda *_args, **_kwargs: None
        monkeypatch.setitem(sys.modules, "fuse_cell", fake_fuse_cell)
        monkeypatch.setitem(sys.modules, "r2_store", types.ModuleType("r2_store"))
    import recon_worker

    job = recon_worker.Job(17, "sess-1", "1:2", 1)
    outcomes = []

    class _Connection:
        def transaction(self):
            from contextlib import nullcontext
            return nullcontext()

        def rollback(self):
            pass

    def busy_process(_conn, _job):
        raise gpu_admission.GpuBusy({"owner": "enhancement", "pid": 99})

    def record_defer(_conn, deferred_job, reason):
        outcomes.append((deferred_job.id, reason))

    monkeypatch.setattr(
        recon_worker, "activate_reviewed_candidates", lambda _conn: 0
    )
    monkeypatch.setattr(recon_worker, "claim_job", lambda _conn: job)
    monkeypatch.setattr(recon_worker, "process_job", busy_process)
    monkeypatch.setattr(recon_worker, "defer_job", record_defer)
    monkeypatch.setattr(
        recon_worker,
        "_record_outcome",
        lambda conn, fn, *args: fn(conn, *args),
    )

    assert recon_worker.run_once(_Connection()) is True
    assert outcomes == [(17, "GPU busy: owner=enhancement, pid=99")]


def test_scrub_gate_disabled_by_either_env_switch(db, monkeypatch):
    import recon_worker

    dsn, conn = db
    for var in ("PREFER_SCRUBBED_VIDEO", "REQUIRE_SCRUBBED_VIDEO"):
        monkeypatch.setenv("PREFER_SCRUBBED_VIDEO", "1")
        monkeypatch.setenv("REQUIRE_SCRUBBED_VIDEO", "1")
        assert recon_worker.scrub_gate_enabled() is True
        monkeypatch.setenv(var, "0")
        assert recon_worker.scrub_gate_enabled() is False
