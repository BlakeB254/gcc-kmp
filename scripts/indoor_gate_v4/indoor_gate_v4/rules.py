"""Weighted indoor/outdoor scene scoring — port of TrashDetectionRules.kt."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class SceneVote(StrEnum):
    INDOOR = "INDOOR"
    OUTDOOR = "OUTDOOR"
    NONE = "NONE"


SCENE_CONFIDENCE = 0.3
SCENE_SCORE_MIN = 0.5
INDOOR_DOMINANCE = 1.25

INDOOR_KEYWORDS = frozenset(
    {
        "indoor",
        "room",
        "ceiling",
        "wall",
        "kitchen",
        "bathroom",
        "bedroom",
        "office",
        "hallway",
        "furniture",
        "sofa",
        "couch",
        "desk",
        "carpet",
        "curtain",
        "shelf",
        "stairs",
        "interior",
        "chair",
        "table",
        "television",
        "lamp",
        "bed",
        "cabinet",
        "refrigerator",
        "laptop",
        "monitor",
        "screen",
        "keyboard",
        "bookcase",
        "book",
        "rug",
        "pillow",
        "blanket",
        "sink",
        "counter",
        "cupboard",
        "appliance",
        "tile",
        "computer",
        "floor",
        "flooring",
        "hardwood",
        "printer",
        "document",
        "paper",
        "drawer",
        "wardrobe",
        "closet",
        "mug",
        "houseplant",
        "blinds",
        "mirror",
        "doorway",
        "fireplace",
        "dresser",
        "bathtub",
        "shower",
        "oven",
        "stove",
        "microwave",
        "dishwasher",
        "staircase",
        "toilet",
    }
)

OUTDOOR_KEYWORDS = frozenset(
    {
        "outdoor",
        "street",
        "road",
        "sidewalk",
        "sky",
        "tree",
        "grass",
        "park",
        "building",
        "asphalt",
        "pavement",
        "cloud",
        "field",
        "vehicle",
        "car",
        "house",
        "fence",
        "yard",
        "highway",
        "lawn",
        "garden",
        "trail",
        "bush",
        "truck",
        "bicycle",
        "traffic",
        "bridge",
        "alley",
        "playground",
        "landscape",
        "horizon",
        "soil",
        "dirt",
        "gravel",
        "sand",
        "mud",
        "concrete",
    }
)


@dataclass(frozen=True, slots=True)
class ClassificationObservation:
    identifier: str
    confidence: float


def tokens_of(identifier: str) -> list[str]:
    return [part for part in identifier.lower().replace(",", " ").replace("_", " ").split() if part]


def scene_scores(observations: list[ClassificationObservation]) -> tuple[float, float]:
    indoor = 0.0
    outdoor = 0.0
    for obs in observations:
        if obs.confidence < SCENE_CONFIDENCE:
            continue
        tokens = tokens_of(obs.identifier)
        if any(token in INDOOR_KEYWORDS for token in tokens):
            indoor += obs.confidence
        if any(token in OUTDOOR_KEYWORDS for token in tokens):
            outdoor += obs.confidence
    return indoor, outdoor


def scene_vote_from(observations: list[ClassificationObservation]) -> SceneVote:
    indoor, outdoor = scene_scores(observations)
    if indoor >= SCENE_SCORE_MIN and indoor > outdoor * INDOOR_DOMINANCE:
        return SceneVote.INDOOR
    if outdoor >= SCENE_SCORE_MIN and outdoor >= indoor:
        return SceneVote.OUTDOOR
    return SceneVote.NONE
