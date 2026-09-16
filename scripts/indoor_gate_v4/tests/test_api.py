import json
from pathlib import Path

from fastapi.testclient import TestClient

from indoor_gate_v4.app import app

client = TestClient(app)
FIXTURE = Path(__file__).parent / "fixtures" / "validation_set.json"


def test_healthz():
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json()["model"] == "indoor_gate_v4"


def test_classify_residence_window_view_indoor():
    response = client.post(
        "/v1/indoor-gate/classify",
        json={
            "observations": [
                {"identifier": "living_room", "confidence": 0.6},
                {"identifier": "sofa", "confidence": 0.4},
                {"identifier": "tree", "confidence": 0.5},
            ],
            "events": [
                {"kind": "scene", "vote": "INDOOR", "timestamp_ms": 1_000_000},
                {"kind": "scene", "vote": "INDOOR", "timestamp_ms": 1_001_000},
            ],
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["scene_vote"] == "INDOOR"
    assert body["state"] == "INDOOR"
    assert body["capture_allowed"] is False
    assert body["elapsed_ms"] < 5000


def test_classify_street_scan_outdoor():
    response = client.post(
        "/v1/indoor-gate/classify",
        json={
            "observations": [
                {"identifier": "street", "confidence": 0.7},
                {"identifier": "sidewalk", "confidence": 0.5},
            ],
            "events": [
                {"kind": "scene", "vote": "OUTDOOR", "timestamp_ms": 1_000_000},
                {"kind": "scene", "vote": "OUTDOOR", "timestamp_ms": 1_000_750},
            ],
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["state"] == "OUTDOOR"
    assert body["capture_allowed"] is True


def test_scene_event_requires_vote():
    response = client.post(
        "/v1/indoor-gate/classify",
        json={"events": [{"kind": "scene", "timestamp_ms": 1}]},
    )
    assert response.status_code == 422


def test_validation_set_meets_accuracy_and_latency():
    cases = json.loads(FIXTURE.read_text())
    correct = 0
    for case in cases:
        started = client.post("/v1/indoor-gate/classify", json=case["request"])
        assert started.status_code == 200, case["id"]
        body = started.json()
        assert body["elapsed_ms"] < 5000, case["id"]
        if body["state"] == case["expected_state"]:
            correct += 1
    accuracy = correct / len(cases)
    assert accuracy >= 0.80, f"accuracy {accuracy:.2%} below 80% ({correct}/{len(cases)})"
