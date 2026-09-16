"""Indoor Gate v4 — server-side replica of the on-device OutdoorGate policy."""

from .gate import OutdoorGate, OutdoorState, SceneVote
from .rules import ClassificationObservation, scene_scores, scene_vote_from

__all__ = [
    "ClassificationObservation",
    "OutdoorGate",
    "OutdoorState",
    "SceneVote",
    "scene_scores",
    "scene_vote_from",
]
MODEL_ID = "indoor_gate_v4"
