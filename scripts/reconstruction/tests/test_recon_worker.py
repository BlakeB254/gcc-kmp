"""recon_worker queue semantics, exercised against the LOCAL scratch
Postgres (data-postgres, :5432) -- SKIP LOCKED / lease-expiry behavior is
Postgres-generic, so there's no reason to hit Neon from tests. Skipped
entirely when the local container isn't reachable."""
import os

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
  completed_at timestamptz
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
"""


@pytest.fixture()
def db():
    admin = _admin_conn()
    admin.execute(f'DROP DATABASE IF EXISTS {TEST_DB} (FORCE)')
    admin.execute(f'CREATE DATABASE {TEST_DB}')
    dsn = TEST_DSN_ADMIN.rsplit("/", 1)[0] + f"/{TEST_DB}"
    conn = psycopg.connect(dsn)
    with conn.cursor() as cur:
        cur.execute(SCHEMA)
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


def test_fail_job_requeues_then_fails_at_max_attempts(db):
    import recon_worker

    dsn, conn = db
    _enqueue(conn, "sess-1", "1:2")

    with conn.transaction():
        job = recon_worker.claim_job(conn)
    recon_worker.fail_job(conn, job, RuntimeError("boom 1"))
    with conn.cursor() as cur:
        cur.execute("SELECT status, last_error FROM reconstruction_jobs WHERE id=%s", (job.id,))
        status, last_error = cur.fetchone()
    assert status == "queued"
    assert "boom 1" in last_error

    # Two more claim+fail cycles hit MAX_ATTEMPTS (3) -> failed for good.
    for n in (2, 3):
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
