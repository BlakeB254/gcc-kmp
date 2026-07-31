"""The capture_chunks index is ground truth for a session's chunk keys; the
iOS manifest is not. These tests pin the reconciliation that closes the gap.

Real failure this reproduces (2026-07-28, session env-...bm4m21): the phone
wrote its manifest mid-session, listing ONE key (chunk-0.jsonl), while all 8
capture artifacts reached R2 minutes later. Fusion is manifest-driven, so it
saw a pose sidecar with no depth behind it -> "0 with usable depth-backed
frames" -> the whole 6-minute walk was lost. The R2-listing indexer had every
row the entire time.
"""
import os
import sys

import psycopg
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import chunk_index  # noqa: E402

TEST_DB = "gcc_chunk_index_test"
TEST_DSN_ADMIN = os.environ.get(
    "RECON_TEST_ADMIN_DSN",
    "postgresql://cdx_admin:cdx_secure_2024@localhost:5432/cdx_platform",
)

SCHEMA = """
CREATE TABLE scan_sessions (
  id serial PRIMARY KEY,
  session_id varchar(64) NOT NULL UNIQUE,
  location_cell_id varchar(32)
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


def _admin_conn():
    conn = psycopg.connect(TEST_DSN_ADMIN, autocommit=True)
    return conn


@pytest.fixture()
def db():
    try:
        admin = _admin_conn()
    except psycopg.OperationalError as err:  # pragma: no cover - env dependent
        pytest.skip(f"no local postgres for chunk_index tests: {err}")
    admin.execute(f"DROP DATABASE IF EXISTS {TEST_DB} (FORCE)")
    admin.execute(f"CREATE DATABASE {TEST_DB}")
    dsn = TEST_DSN_ADMIN.rsplit("/", 1)[0] + f"/{TEST_DB}"
    conn = psycopg.connect(dsn)
    with conn.cursor() as cur:
        cur.execute(SCHEMA)
    conn.commit()
    yield dsn, conn
    conn.close()
    admin.execute(f"DROP DATABASE IF EXISTS {TEST_DB} (FORCE)")
    admin.close()


def _session(conn, session_id, cell):
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO scan_sessions (session_id, location_cell_id) VALUES (%s, %s)",
            (session_id, cell),
        )
    conn.commit()


def _chunk(conn, session_id, cell, key, fmt):
    with conn.cursor() as cur:
        cur.execute(
            """INSERT INTO capture_chunks
               (session_id, location_cell_id, artifact_type, r2_key, format)
               VALUES (%s, %s, %s, %s, %s)""",
            (session_id, cell, "video" if fmt == "mov" else "other", key, fmt),
        )
    conn.commit()


def test_chunk_keys_by_session_returns_indexed_capture_artifacts(db):
    dsn, conn = db
    _session(conn, "env-a", "4646:-7218")
    _chunk(conn, "env-a", "4646:-7218", "raw/4646:-7218/env-a/capture/chunk-0.bin", "bin")
    _chunk(conn, "env-a", "4646:-7218", "raw/4646:-7218/env-a/capture/chunk-0.confidence", "confidence")
    _chunk(conn, "env-a", "4646:-7218", "raw/4646:-7218/env-a/capture/chunk-0.jsonl", "jsonl")
    _chunk(conn, "env-a", "4646:-7218", "raw/4646:-7218/env-a/capture/chunk-0.mov", "mov")

    keys = chunk_index.chunk_keys_by_session(conn, "4646:-7218")

    assert set(keys) == {"env-a"}
    assert keys["env-a"] == [
        "raw/4646:-7218/env-a/capture/chunk-0.bin",
        "raw/4646:-7218/env-a/capture/chunk-0.confidence",
        "raw/4646:-7218/env-a/capture/chunk-0.jsonl",
        "raw/4646:-7218/env-a/capture/chunk-0.mov",
    ]


def test_chunk_keys_join_on_session_not_cell_prefix(db):
    """The real bm4m21 shape: the session is indexed under one cell but GPS
    drifted, so its chunks were written under a NEIGHBORING cell prefix. The
    join must follow session_id, exactly as the scrub gate's query does."""
    dsn, conn = db
    _session(conn, "env-drift", "464636:-721840")
    _chunk(conn, "env-drift", "464636:-721841",
           "raw/464636:-721841/env-drift/capture/chunk-0.bin", "bin")
    _chunk(conn, "env-drift", "464636:-721841",
           "raw/464636:-721841/env-drift/capture/chunk-0.mov", "mov")

    keys = chunk_index.chunk_keys_by_session(conn, "464636:-721840")

    assert keys["env-drift"] == [
        "raw/464636:-721841/env-drift/capture/chunk-0.bin",
        "raw/464636:-721841/env-drift/capture/chunk-0.mov",
    ]


def test_chunk_keys_ignores_non_capture_artifacts(db):
    """Photos, manifests and event logs are indexed too — they are not chunks
    and must never reach _group_chunks_by_index."""
    dsn, conn = db
    _session(conn, "env-b", "4646:-7218")
    _chunk(conn, "env-b", "4646:-7218", "raw/4646:-7218/env-b/capture/chunk-0.bin", "bin")
    _chunk(conn, "env-b", "4646:-7218", "raw/4646:-7218/dev/env-b-photo0.jpg", "jpg")
    _chunk(conn, "env-b", "4646:-7218", "raw/4646:-7218/dev/env-b-manifest.json", "json")
    _chunk(conn, "env-b", "4646:-7218", "raw/4646:-7218/env-b/events.jsonl.gz", "gz")

    keys = chunk_index.chunk_keys_by_session(conn, "4646:-7218")

    assert keys["env-b"] == ["raw/4646:-7218/env-b/capture/chunk-0.bin"]


def test_reconcile_adds_indexed_keys_the_manifest_omitted():
    """bm4m21 exactly: manifest listed 1 of 8."""
    manifest = {
        "sessionId": "env-bm4m21",
        "captureChunks": ["raw/c/env-bm4m21/capture/chunk-0.jsonl"],
    }
    indexed = {
        "env-bm4m21": [
            "raw/c/env-bm4m21/capture/chunk-0.bin",
            "raw/c/env-bm4m21/capture/chunk-0.jsonl",
            "raw/c/env-bm4m21/capture/chunk-0.mov",
        ]
    }

    added = chunk_index.reconcile_manifest_chunks([manifest], indexed)

    assert added == 2
    assert manifest["captureChunks"] == [
        "raw/c/env-bm4m21/capture/chunk-0.bin",
        "raw/c/env-bm4m21/capture/chunk-0.jsonl",
        "raw/c/env-bm4m21/capture/chunk-0.mov",
    ]


def test_reconcile_keeps_manifest_only_keys():
    """Union, not replace: if the indexer is lagging, a key the manifest
    already had must not be dropped."""
    manifest = {
        "sessionId": "env-x",
        "captureChunks": ["raw/c/env-x/capture/chunk-9.mov"],
    }
    indexed = {"env-x": ["raw/c/env-x/capture/chunk-0.bin"]}

    added = chunk_index.reconcile_manifest_chunks([manifest], indexed)

    assert added == 1
    assert set(manifest["captureChunks"]) == {
        "raw/c/env-x/capture/chunk-0.bin",
        "raw/c/env-x/capture/chunk-9.mov",
    }


def test_reconcile_is_a_noop_when_manifest_already_complete():
    manifest = {
        "sessionId": "env-ok",
        "captureChunks": [
            "raw/c/env-ok/capture/chunk-0.bin",
            "raw/c/env-ok/capture/chunk-0.mov",
        ],
    }
    indexed = {"env-ok": list(manifest["captureChunks"])}

    added = chunk_index.reconcile_manifest_chunks([manifest], indexed)

    assert added == 0
    assert manifest["captureChunks"] == [
        "raw/c/env-ok/capture/chunk-0.bin",
        "raw/c/env-ok/capture/chunk-0.mov",
    ]


def test_reconcile_ignores_sessions_with_no_index_rows():
    manifest = {"sessionId": "env-unindexed", "captureChunks": ["raw/c/k/chunk-0.bin"]}

    added = chunk_index.reconcile_manifest_chunks([manifest], {})

    assert added == 0
    assert manifest["captureChunks"] == ["raw/c/k/chunk-0.bin"]


def test_reconcile_seeds_captureChunks_when_manifest_has_none():
    """A manifest written before ANY chunk uploaded has no captureChunks key
    at all — the index still knows what landed."""
    manifest = {"sessionId": "env-empty"}
    indexed = {"env-empty": ["raw/c/env-empty/capture/chunk-0.bin"]}

    added = chunk_index.reconcile_manifest_chunks([manifest], indexed)

    assert added == 1
    assert manifest["captureChunks"] == ["raw/c/env-empty/capture/chunk-0.bin"]


def test_reconcile_falls_back_to_manifest_key_when_sessionId_missing():
    """list_manifests_for_cell keys sessions by manifest key when sessionId is
    absent; reconciliation must use the same identity or silently no-op."""
    manifest = {"captureChunks": ["raw/c/k/chunk-0.bin"]}

    added = chunk_index.reconcile_manifest_chunks([manifest], {"whatever": ["x"]})

    assert added == 0


def test_chunk_keys_for_cell_is_fail_soft_without_database_url(monkeypatch):
    """Fusion must never start failing because the index is unreachable — a
    missing DSN degrades to manifest-only behaviour, not an exception."""
    monkeypatch.delenv("DATABASE_URL", raising=False)

    assert chunk_index.chunk_keys_for_cell("4646:-7218") == {}


def test_chunk_keys_for_cell_is_fail_soft_on_connection_error(monkeypatch):
    monkeypatch.setenv("DATABASE_URL", "postgresql://nobody@127.0.0.1:1/nope")

    assert chunk_index.chunk_keys_for_cell("4646:-7218") == {}
