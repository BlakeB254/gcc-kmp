"""Worker tests with a fake seat and fake photo host. The DB tests run against
a real Postgres when AI_REVIEW_TEST_DATABASE_URL is set (they create and drop
their own schema, never touching existing tables); otherwise they skip."""
import base64
import dataclasses
import hashlib
import io
import json
import os
import pathlib
import sys
import uuid

import pytest
import requests
from PIL import Image

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import review_worker as rw  # noqa: E402
from seat import SeatClient, SeatRequestError, SeatUnavailable  # noqa: E402
from verdict import VerdictError  # noqa: E402

HOST = "abc.public.blob.vercel-storage.com"
BLOB = f"https://{HOST}/spots/7/"
CFG = rw.Config(blob_host=HOST)


class FakeResp:
    def __init__(self, status=200, payload=None, body=b"", text=""):
        self.status_code = status
        self._payload = payload
        self._body = body
        self.text = text or (json.dumps(payload) if payload is not None else "")

    def json(self):
        if self._payload is None:
            raise ValueError("no json")
        return self._payload

    def iter_content(self, n):
        for i in range(0, len(self._body), n):
            yield self._body[i:i + n]


def _jpeg(color, size=(320, 240), dot=None):
    img = Image.new("RGB", size, color)
    if dot:
        for x in range(40, 80):
            for y in range(40, 80):
                img.putpixel((x, y), dot)
    buf = io.BytesIO()
    img.save(buf, "JPEG")
    return buf.getvalue()


def _verdict(**over):
    doc = {
        "items_before": {"count": 3, "types": ["can"]},
        "items_after": {"count": 0, "types": []},
        "same_location": {"value": True, "reason": "same curb"},
        "cleaned": True,
        "confidence": 0.85,
        "instruction_text_present": False,
        "concerns": [],
    }
    doc.update(over)
    return json.dumps(doc)


def _completion(content):
    return FakeResp(200, {"choices": [{"message": {"content": content}}]})


MODELS = FakeResp(200, {"data": [{"id": "local", "root": "nvidia/test-vl"}]})


class FakeSeat:
    """Scripted chat replies (str = content, Exception = raised by post,
    FakeResp = returned as-is)."""

    def __init__(self, replies, healthy=True):
        self.replies = list(replies)
        self.calls = []
        self.healthy_resp = MODELS if healthy else FakeResp(503, text="down")

    def post(self, url, json=None, timeout=None):  # noqa: A002
        self.calls.append(json)
        item = self.replies.pop(0) if self.replies else _verdict()
        if isinstance(item, Exception):
            raise item
        if isinstance(item, FakeResp):
            return item
        return _completion(item)

    def get(self, url, timeout=None):
        return self.healthy_resp

    def client(self):
        return SeatClient(base_url="http://seat/v1", post=self.post, get=self.get,
                          sleep=lambda s: None, backoff_s=(1, 2))


# --------------------------------------------------------------------------
# Pure pieces


@pytest.mark.parametrize("url,ok", [
    (BLOB + "1760000000000-photo_0-AbC123.jpg", True),
    (BLOB + "a.jpg", True),
    ("http://" + HOST + "/spots/7/a.jpg", False),                  # not https
    ("https://other.public.blob.vercel-storage.com/spots/7/a.jpg", False),  # not OUR store
    ("https://evil.example.com/spots/7/a.jpg", False),
    ("https://x." + HOST + "/spots/7/a.jpg", False),               # suffix trick
    ("https://user:pw@" + HOST + "/spots/7/a.jpg", False),  # ggignore
    ("https://" + HOST + ":8443/spots/7/a.jpg", False),
    ("https://" + HOST + ":notaport/spots/7/a.jpg", False),
    (BLOB + "a.jpg?download=1", False),                            # query
    (BLOB + "a.jpg#x", False),                                     # fragment
    ("https://" + HOST + "/spots/8/a.jpg", False),                 # another worker
    ("https://" + HOST + "/spots/7/sub/a.jpg", False),             # nested path
    ("https://" + HOST + "/spots/7/../8/a.jpg", False),
    ("https://" + HOST + "/spots/7/", False),
    ("https://" + HOST + "/other/7/a.jpg", False),
    ("https://169.254.169.254/latest/meta-data", False),
])
def test_url_rule(url, ok):
    assert rw.url_allowed(url, CFG, 7) is ok


def test_url_rule_fails_closed_without_host_or_worker():
    assert not rw.url_allowed(BLOB + "a.jpg", rw.Config(), 7)
    assert not rw.url_allowed(BLOB + "a.jpg", CFG, None)
    test_cfg = rw.Config(loopback_test_origins=("http://127.0.0.1:9999",))
    assert rw.url_allowed("http://127.0.0.1:9999/spots/7/x.jpg", test_cfg, 7)
    assert not rw.url_allowed("http://127.0.0.1:9999/x.jpg", test_cfg, 7)  # path rule still applies
    # Exact origin only: no prefix tricks, other ports or other hosts.
    assert not rw.url_allowed("http://127.0.0.1:99990/spots/7/x.jpg", test_cfg, 7)
    assert not rw.url_allowed("http://127.0.0.1:9999@evil.example.com/spots/7/x.jpg", test_cfg, 7)
    assert not rw.url_allowed("http://evil.example.com:9999/spots/7/x.jpg",
                              rw.Config(loopback_test_origins=("http://evil.example.com:9999",)), 7)
    # And it can no longer come from the environment.
    assert rw.Config.from_env({"AI_REVIEW_EXTRA_URL_PREFIXES": "https://"}).loopback_test_origins == ()


def test_own_blob_host_mirrors_the_site():
    assert rw.own_blob_host("vercel_blob_rw_AbC123_secret") == "abc123.public.blob.vercel-storage.com"
    assert rw.own_blob_host(None) is None and rw.own_blob_host("nope") is None
    cfg = rw.Config.from_env({"BLOB_READ_WRITE_TOKEN": "vercel_blob_rw_Store9_x"})
    assert cfg.blob_host == "store9.public.blob.vercel-storage.com"
    assert rw.Config.from_env({"AI_REVIEW_BLOB_HOST": "X.public.blob.vercel-storage.com"}).blob_host == \
        "x.public.blob.vercel-storage.com"


def test_evidence_urls_accepts_every_stored_shape():
    raw = [{"url": BLOB + "1.jpg", "capturedAt": "t"}, BLOB + "2.jpg", 42, 99, True, {"x": 1}]
    assert rw.evidence_urls(raw, {42: BLOB + "m42.jpg"}) == [BLOB + "1.jpg", BLOB + "2.jpg", BLOB + "m42.jpg"]
    assert rw.evidence_urls(None, {}) == []
    assert rw.legacy_media_ids(raw, [7, 42]) == [7, 42, 99]


def _bp(before, after, hotspots, max_pairs=4, worker_id=7):
    return rw.build_pairs(before, after, hotspots, max_pairs, worker_id=worker_id, cfg=CFG)


def test_build_pairs_zips_evidence_and_adds_hotspots():
    pairs, concerns = _bp(
        [BLOB + "b1.jpg", BLOB + "b2.jpg"], [BLOB + "a1.jpg"],
        [(5, BLOB + "hb.jpg", BLOB + "ha.jpg"), (6, BLOB + "hb2.jpg", None)],
    )
    assert [p.label for p in pairs] == ["evidence #1", "hotspot 5"]
    assert any("counts differ" in c for c in concerns)
    assert any("no after photo" in c for c in concerns)


def test_build_pairs_rejects_foreign_urls_as_concerns():
    pairs, concerns = _bp(
        [BLOB + "b.jpg", "https://evil.example.com/x.jpg"], [BLOB + "a.jpg", BLOB + "a2.jpg"],
        [(5, "https://" + HOST + "/spots/8/hb.jpg", BLOB + "ha.jpg")],
    )
    assert [p.label for p in pairs] == ["evidence #1"]
    assert sum("not an upload by this job's worker" in c for c in concerns) == 2


def test_build_pairs_reserves_slots_for_hotspots_and_says_so():
    ev = [BLOB + f"e{i}.jpg" for i in range(6)]
    hs = [(i, BLOB + f"hb{i}.jpg", BLOB + f"ha{i}.jpg") for i in range(6)]
    pairs, concerns = _bp(ev, ev, hs, max_pairs=4)
    assert [p.label for p in pairs] == ["evidence #1", "evidence #2", "hotspot 0", "hotspot 1"]
    assert any("only 4 of 12" in c for c in concerns)
    # Unused hotspot slots go to evidence.
    pairs, _ = _bp(ev, ev, hs[:1], max_pairs=4)
    assert [p.label for p in pairs].count("hotspot 0") == 1 and len(pairs) == 4


def test_input_hash_changes_with_evidence_and_threshold():
    p = [rw.Pair("evidence #1", "b", "a")]
    h = rw.input_hash(p, [], "local", 0.6)
    assert h == rw.input_hash(list(p), [], "local", 0.6)
    assert h != rw.input_hash([rw.Pair("evidence #1", "b", "a2")], [], "local", 0.6)
    assert h != rw.input_hash(p, [], "local", 0.7)


def test_prepare_image_downscales_and_detects_identical():
    big = _jpeg((10, 120, 10), size=(4000, 3000))
    a = rw.prepare_image(big, 1024)
    img = Image.open(io.BytesIO(base64.b64decode(a.data_url.split(",", 1)[1])))
    assert max(img.size) == 1024
    assert rw.identical_reason(a, rw.prepare_image(big, 1024))
    other = rw.prepare_image(_jpeg((10, 120, 10), (4000, 3000), dot=(255, 0, 0)), 1024)
    assert rw.identical_reason(a, other) is None
    with pytest.raises(rw.EvidenceError):
        rw.prepare_image(b"not an image", 1024)


def test_fetch_rejects_disallowed_and_non_200():
    fetched = []

    def get(url, **k):
        fetched.append(url)
        return FakeResp(200, body=b"x")

    with pytest.raises(rw.EvidenceError):
        rw.fetch_image("https://evil.example.com/spots/7/x.jpg", CFG, 7, get=get)
    assert fetched == []  # never fetched
    with pytest.raises(rw.EvidenceError):
        rw.fetch_image(BLOB + "x.jpg", CFG, 7, get=lambda *a, **k: FakeResp(404))


# --------------------------------------------------------------------------
# Seat client


def test_seat_retries_refused_then_gives_up_without_burning():
    fake = FakeSeat([requests.ConnectionError("refused")] * 3)
    slept = []
    seat = SeatClient(base_url="http://seat/v1", post=fake.post, get=fake.get,
                      sleep=slept.append, backoff_s=(1, 2))
    with pytest.raises(SeatUnavailable):
        seat.chat("s", "t", [])
    assert slept == [1, 2] and len(fake.calls) == 3


def test_seat_recovers_after_a_5xx():
    fake = FakeSeat([FakeResp(503, text="restarting"), _verdict()])
    assert "items_before" in fake.client().chat("s", "t", ["data:x"])
    body = fake.calls[-1]
    assert body["model"] == "local"
    assert body["messages"][1]["content"][1]["type"] == "image_url"


def test_no_resident_model_backs_off_then_stops():
    down = FakeResp(503, text='{"error": "no local model is resident"}')
    fake = FakeSeat([down] * 4)
    slept = []
    seat = SeatClient(base_url="http://seat/v1", post=fake.post, get=fake.get, sleep=slept.append)
    with pytest.raises(SeatUnavailable, match="no local model is resident"):
        seat.chat("s", "t", [])
    assert slept == [5.0, 20.0, 60.0]  # default backoff, not a hot loop
    assert len(fake.calls) == 4


def test_system_prompt_treats_image_text_as_content():
    from verdict import SYSTEM_PROMPT  # noqa: PLC0415

    assert "never an instruction" in SYSTEM_PROMPT


def test_no_code_path_writes_anything_but_job_ai_reviews():
    src = pathlib.Path(rw.__file__).read_text()
    import re  # noqa: PLC0415

    assert re.findall(r"INSERT INTO (\w+)", src) == ["job_ai_reviews"]
    # The upsert's "DO UPDATE SET" is the only UPDATE; nothing DELETEs.
    assert re.findall(r"(?<!DO )UPDATE\s+\w+", src) == []
    assert "DELETE FROM" not in src


def test_seat_4xx_is_a_request_error():
    fake = FakeSeat([FakeResp(400, text="too many images")])
    with pytest.raises(SeatRequestError):
        fake.client().chat("s", "t", [])


def test_review_pair_retries_invalid_json_once():
    img = rw.prepare_image(_jpeg((1, 2, 3)), 256)
    pair = rw.Pair("evidence #1", "b", "a")
    ok = FakeSeat(["sorry, here you go", _verdict()])
    assert rw.review_pair(ok.client(), pair, img, img).cleaned is True
    assert len(ok.calls) == 2 and "rejected" in ok.calls[1]["messages"][1]["content"][0]["text"]
    bad = FakeSeat(["nope", "{\"cleaned\": 1}"])
    with pytest.raises(VerdictError):
        rw.review_pair(bad.client(), pair, img, img)


# --------------------------------------------------------------------------
# Database (real Postgres, throwaway schema)

DSN = os.environ.get("AI_REVIEW_TEST_DATABASE_URL")
needs_db = pytest.mark.skipif(not DSN, reason="AI_REVIEW_TEST_DATABASE_URL not set")

SCHEMA_DDL = """
CREATE TABLE jobs (
  id serial PRIMARY KEY, block_id integer NOT NULL, worker_id integer,
  status text DEFAULT 'available', claimed_at timestamptz,
  before_photos jsonb, after_photos jsonb, completion_scan_id integer,
  updated_at timestamptz DEFAULT now() NOT NULL);
CREATE TABLE media (id serial PRIMARY KEY, url varchar);
CREATE TABLE route_scans (id serial PRIMARY KEY, block_id integer, worker_id integer NOT NULL,
  created_at timestamptz DEFAULT now() NOT NULL);
CREATE TABLE scan_hotspots (id serial PRIMARY KEY, scan_id integer NOT NULL,
  before_photo_url varchar(500) NOT NULL, after_photo_url varchar(500));
-- Mirrors the migration that ships with the schema lane.
CREATE TABLE IF NOT EXISTS job_ai_reviews (
  job_id integer PRIMARY KEY REFERENCES jobs(id) ON DELETE CASCADE,
  status text NOT NULL, verdict jsonb NOT NULL, model text NOT NULL,
  input_hash text, created_at timestamptz DEFAULT now(), updated_at timestamptz);
"""


@pytest.fixture
def db():
    psycopg = pytest.importorskip("psycopg")
    schema = f"ai_review_test_{uuid.uuid4().hex[:8]}"
    conn = psycopg.connect(DSN, autocommit=False)
    conn.execute(f"CREATE SCHEMA {schema}")
    conn.execute(f"SET search_path TO {schema}")
    conn.execute(SCHEMA_DDL)
    conn.commit()
    try:
        yield conn
    finally:
        conn.rollback()
        conn.execute(f"DROP SCHEMA {schema} CASCADE")
        conn.commit()
        conn.close()


PHOTOS = {
    BLOB + "before.jpg": _jpeg((90, 90, 90), dot=(250, 250, 250)),
    BLOB + "after.jpg": _jpeg((90, 90, 90)),
    BLOB + "hb.jpg": _jpeg((40, 90, 40), dot=(200, 20, 20)),
    BLOB + "ha.jpg": _jpeg((40, 90, 40)),
}


def fake_get(url, **kw):
    return FakeResp(200, body=PHOTOS[url]) if url in PHOTOS else FakeResp(404)


def _job(conn, before, after, status="pending_review", scan=None):
    return conn.execute(
        "INSERT INTO jobs (block_id, worker_id, status, before_photos, after_photos, completion_scan_id)"
        " VALUES (1, 7, %s, %s::jsonb, %s::jsonb, %s) RETURNING id",
        (status, json.dumps(before), json.dumps(after), scan),
    ).fetchone()[0]


def _ev(name):
    return [{"url": BLOB + name, "capturedAt": "2026-10-18T00:00:00Z"}]


def _review(conn, jid):
    return conn.execute(
        "SELECT status, verdict, model, input_hash FROM job_ai_reviews WHERE job_id=%s", (jid,)
    ).fetchone()


@needs_db
def test_run_records_ok_flagged_and_error(db):
    scan = db.execute(
        "INSERT INTO route_scans (block_id, worker_id) VALUES (1, 7) RETURNING id").fetchone()[0]
    db.execute("INSERT INTO scan_hotspots (scan_id, before_photo_url, after_photo_url) VALUES (%s, %s, %s)",
               (scan, BLOB + "hb.jpg", BLOB + "ha.jpg"))
    good = _job(db, _ev("before.jpg"), _ev("after.jpg"), scan=scan)
    same = _job(db, _ev("before.jpg"), _ev("before.jpg"))
    garbled = _job(db, _ev("before.jpg"), _ev("after.jpg"))
    missing = _job(db, _ev("before.jpg"), _ev("gone.jpg"))
    done = _job(db, _ev("before.jpg"), _ev("after.jpg"), status="completed")
    db.commit()

    # good: evidence pair + hotspot pair; same: the model is fooled but the
    # identical-image check is not; garbled: invalid twice.
    seat = FakeSeat([_verdict(), _verdict(), _verdict(), "nope", "still nope"])
    code = rw.run(db, seat.client(), CFG, get=fake_get)
    assert code == 1  # one job recorded 'error'

    st, v, model, h = _review(db, good)
    assert st == "ok" and len(v["pairs"]) == 2 and v["scan_id"] == scan
    assert v["pairs"][0]["before_sha256"] == hashlib.sha256(PHOTOS[BLOB + "before.jpg"]).hexdigest()
    assert model == "local (nvidia/test-vl)" and h
    st, v, *_ = _review(db, same)
    assert st == "flagged" and "identical" in v["pairs"][0]["flag_reasons"][0]
    st, v, *_ = _review(db, garbled)
    assert st == "error" and "VerdictError" in v["error"]
    st, v, *_ = _review(db, missing)
    assert st == "error" and "HTTP 404" in v["error"]
    assert _review(db, done) is None

    # Second run: nothing changed, so no model calls; errors are not due yet.
    seat2 = FakeSeat([])
    assert rw.run(db, seat2.client(), CFG, get=fake_get) == 0
    assert seat2.calls == []

    # Evidence changes -> that job alone is re-reviewed.
    db.execute("UPDATE jobs SET before_photos=%s::jsonb, after_photos=%s::jsonb WHERE id=%s",
               (json.dumps(_ev("before.jpg") * 2),
                json.dumps(_ev("before.jpg") + _ev("after.jpg")), same))
    db.commit()
    seat3 = FakeSeat([_verdict(), _verdict()])
    rw.run(db, seat3.client(), CFG, get=fake_get)
    assert len(seat3.calls) == 2
    assert _review(db, same)[0] == "flagged"  # first pair is still identical

    # An error becomes due again after the retry window.
    db.execute("UPDATE job_ai_reviews SET updated_at = now() - interval '7 hours' WHERE job_id=%s",
               (garbled,))
    db.commit()
    seat4 = FakeSeat([_verdict()])
    rw.run(db, seat4.client(), CFG, get=fake_get)
    assert _review(db, garbled)[0] == "ok"


@needs_db
def test_seat_outage_records_nothing(db):
    jobs = [_job(db, _ev("before.jpg"), _ev("after.jpg")) for _ in range(5)]
    db.commit()
    seat = FakeSeat([requests.ConnectionError("refused")] * 50)
    assert rw.run(db, seat.client(), CFG, get=fake_get) == 2
    # One job's worth of attempts (1 + 2 retries), then the run stops.
    assert len(seat.calls) == 3
    assert all(_review(db, j) is None for j in jobs)


@needs_db
def test_unhealthy_seat_stops_before_any_job(db):
    _job(db, _ev("before.jpg"), _ev("after.jpg"))
    db.commit()
    seat = FakeSeat([], healthy=False)
    assert rw.run(db, seat.client(), CFG, get=fake_get) == 2
    assert seat.calls == []


@needs_db
def test_job_without_evidence_is_flagged_without_a_model_call(db):
    jid = _job(db, [], [])
    db.commit()
    seat = FakeSeat([])
    rw.run(db, seat.client(), CFG, get=fake_get)
    st, v, *_ = _review(db, jid)
    assert st == "flagged" and seat.calls == []


@needs_db
def test_max_jobs_bounds_a_run(db):
    for _ in range(4):
        _job(db, _ev("before.jpg"), _ev("after.jpg"))
    db.commit()
    rw.run(db, FakeSeat([]).client(), dataclasses.replace(CFG, max_jobs=2), get=fake_get)
    assert db.execute("SELECT count(*) FROM job_ai_reviews").fetchone()[0] == 2


@needs_db
def test_locked_job_is_skipped(db):
    psycopg = pytest.importorskip("psycopg")
    jid = _job(db, _ev("before.jpg"), _ev("after.jpg"))
    db.commit()
    other = psycopg.connect(DSN)
    try:
        with other.transaction():
            other.execute("SELECT pg_advisory_xact_lock(%s, %s)", (rw.AI_REVIEW_LOCK_CLASS, jid))
            seat = FakeSeat([])
            out = rw.review_job(db, jid, seat.client(), CFG, "local", fake_get)
            assert out == "skipped" and seat.calls == []
    finally:
        other.close()


@needs_db
def test_scan_fallback_only_uses_scans_since_the_claim(db):
    old = db.execute("INSERT INTO route_scans (block_id, worker_id, created_at)"
                     " VALUES (1, 7, now() - interval '2 days') RETURNING id").fetchone()[0]
    db.execute("INSERT INTO scan_hotspots (scan_id, before_photo_url, after_photo_url) VALUES (%s, %s, %s)",
               (old, BLOB + "hb.jpg", BLOB + "ha.jpg"))
    jid = _job(db, _ev("before.jpg"), _ev("after.jpg"))
    db.execute("UPDATE jobs SET claimed_at = now() - interval '1 day' WHERE id=%s", (jid,))
    job, pairs, _ = rw.load_job_evidence(db, jid, CFG)
    assert job["scan_id"] is None and len(pairs) == 1
    new = db.execute("INSERT INTO route_scans (block_id, worker_id) VALUES (1, 7) RETURNING id").fetchone()[0]
    db.execute("INSERT INTO scan_hotspots (scan_id, before_photo_url, after_photo_url) VALUES (%s, %s, %s)",
               (new, BLOB + "hb.jpg", BLOB + "ha.jpg"))
    job, pairs, _ = rw.load_job_evidence(db, jid, CFG)
    assert job["scan_id"] == new and pairs[-1].label.startswith("hotspot")


@needs_db
def test_job_concerns_fail_closed_even_when_every_pair_passes(db):
    scan = db.execute(
        "INSERT INTO route_scans (block_id, worker_id) VALUES (1, 7) RETURNING id").fetchone()[0]
    db.execute("INSERT INTO scan_hotspots (scan_id, before_photo_url, after_photo_url) VALUES (%s, %s, NULL)",
               (scan, BLOB + "hb.jpg"))
    missing_after = _job(db, _ev("before.jpg"), _ev("after.jpg"), scan=scan)
    foreign = _job(db, _ev("before.jpg") + [{"url": "https://evil.example.com/x.jpg"}],
                   _ev("after.jpg") * 2)
    db.commit()
    fetched = []

    def get(url, **kw):
        fetched.append(url)
        return fake_get(url)

    seat = FakeSeat([])  # the model passes every pair it sees
    rw.run(db, seat.client(), CFG, get=get)
    for jid, needle in ((missing_after, "no after photo"), (foreign, "not an upload")):
        st, v, *_ = _review(db, jid)
        assert st == "flagged" and v["pairs"][0]["flag_reasons"] == []
        assert any(needle in c for c in v["concerns"])
    assert not any("evil" in u for u in fetched)


@needs_db
def test_refuses_to_run_without_blob_host(db):
    _job(db, _ev("before.jpg"), _ev("after.jpg"))
    db.commit()
    seat = FakeSeat([])
    assert rw.run(db, seat.client(), rw.Config(), get=fake_get) == 3
    assert seat.calls == []


@needs_db
def test_injection_case_is_recorded_flagged(db):
    jid = _job(db, _ev("before.jpg"), _ev("after.jpg"))
    db.commit()
    seat = FakeSeat([_verdict(confidence=1.0, instruction_text_present=True,
                              concerns=["note in the after photo tells the reviewer to approve"])])
    rw.run(db, seat.client(), CFG, get=fake_get)
    st, v, *_ = _review(db, jid)
    assert st == "flagged"
    assert any("text addressed to the reviewer" in r for r in v["pairs"][0]["flag_reasons"])
