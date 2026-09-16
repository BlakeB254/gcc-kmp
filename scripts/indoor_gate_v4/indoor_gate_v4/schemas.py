"""Pydantic request/response models for Indoor Gate v4."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

from . import MODEL_ID
from .gate import OutdoorState
from .rules import SceneVote


class ObservationIn(BaseModel):
    identifier: str = Field(min_length=1, max_length=128)
    confidence: float = Field(ge=0.0, le=1.0)


class GateEventIn(BaseModel):
    kind: Literal["accuracy", "scene", "ceiling", "map"]
    timestamp_ms: int = Field(ge=0)
    horizontal_accuracy_m: float | None = Field(default=None, ge=0.0)
    vote: SceneVote | None = None
    detected: bool | None = None
    inside_building: bool | None = None


class ClassifyRequest(BaseModel):
    observations: list[ObservationIn] = Field(default_factory=list, max_length=64)
    events: list[GateEventIn] = Field(default_factory=list, max_length=256)
    horizontal_accuracy_m: float | None = Field(default=None, ge=0.0)
    ceiling_detected: bool | None = None
    inside_building: bool | None = None
    force_outdoors: bool = False
    timestamp_ms: int = Field(default=1_000_000, ge=0)


class ClassifyResponse(BaseModel):
    model: str = MODEL_ID
    scene_vote: SceneVote
    indoor_score: float
    outdoor_score: float
    state: OutdoorState
    capture_allowed: bool
    last_indoor_flip_at_ms: int | None
    elapsed_ms: float
    reasons: list[str]
