"""FastAPI router for Indoor Gate v4 classification."""

from __future__ import annotations

import time
from collections.abc import Sequence

from fastapi import FastAPI, HTTPException

from . import MODEL_ID
from .gate import OutdoorGate
from .rules import ClassificationObservation, SceneVote, scene_scores, scene_vote_from
from .schemas import ClassifyRequest, ClassifyResponse, GateEventIn, ObservationIn


def observations_from(items: Sequence[ObservationIn]) -> list[ClassificationObservation]:
    return [ClassificationObservation(item.identifier, item.confidence) for item in items]


def apply_event(gate: OutdoorGate, event: GateEventIn) -> None:
    if event.kind == "accuracy":
        gate.on_accuracy_sample(event.horizontal_accuracy_m, event.timestamp_ms)
        return
    if event.kind == "scene":
        if event.vote is None:
            raise HTTPException(status_code=422, detail="scene event requires vote")
        gate.on_scene_vote(event.vote, event.timestamp_ms)
        return
    if event.kind == "ceiling":
        if event.detected is None:
            raise HTTPException(status_code=422, detail="ceiling event requires detected")
        gate.on_ceiling_plane(event.detected, event.timestamp_ms)
        return
    if event.inside_building is None:
        raise HTTPException(status_code=422, detail="map event requires inside_building")
    gate.on_map_evidence(event.inside_building, event.timestamp_ms)


def classify(request: ClassifyRequest) -> ClassifyResponse:
    started = time.perf_counter()
    observations = observations_from(request.observations)
    indoor_score, outdoor_score = scene_scores(observations)
    vote = scene_vote_from(observations)
    gate = OutdoorGate()
    if request.force_outdoors:
        gate.force_outdoors()
    for event in request.events:
        apply_event(gate, event)
    tick = request.timestamp_ms
    kinds = {event.kind for event in request.events}
    if request.horizontal_accuracy_m is not None:
        gate.on_accuracy_sample(request.horizontal_accuracy_m, tick)
    if observations and "scene" not in kinds:
        gate.on_scene_vote(vote, tick)
    if request.ceiling_detected is not None:
        gate.on_ceiling_plane(request.ceiling_detected, tick)
    if request.inside_building is not None:
        gate.on_map_evidence(request.inside_building, tick)
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    return ClassifyResponse(
        scene_vote=vote,
        indoor_score=round(indoor_score, 4),
        outdoor_score=round(outdoor_score, 4),
        state=gate.state,
        capture_allowed=gate.capture_allowed,
        last_indoor_flip_at_ms=gate.last_indoor_flip_at_ms,
        elapsed_ms=round(elapsed_ms, 3),
        reasons=gate.reasons[-8:],
    )


def create_app() -> FastAPI:
    app = FastAPI(title="GCC Indoor Gate v4", version="1.0.0")

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok", "model": MODEL_ID}

    @app.get("/v1/indoor-gate/model")
    def model_card() -> dict[str, object]:
        return {
            "id": MODEL_ID,
            "policy": "TrashDetectionRules + OutdoorGate v4 (shipped client replica)",
            "inputs": "Vision/ML Kit label packets, GPS accuracy, ceiling plane, map containment",
            "does_not": "ingest pixels, generate imagery, or persist frames",
        }

    @app.post("/v1/indoor-gate/classify", response_model=ClassifyResponse)
    def classify_route(request: ClassifyRequest) -> ClassifyResponse:
        return classify(request)

    return app


app = create_app()
