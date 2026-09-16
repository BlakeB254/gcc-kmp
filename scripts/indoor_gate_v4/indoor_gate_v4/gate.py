"""OutdoorGate v4 fusion — port of composeApp OutdoorGate.kt (shipped policy)."""

from __future__ import annotations

from collections import deque
from enum import StrEnum

from .rules import SceneVote


class OutdoorState(StrEnum):
    OUTDOOR = "OUTDOOR"
    UNCERTAIN = "UNCERTAIN"
    INDOOR = "INDOOR"


class OutdoorGate:
    EVIDENCE_WINDOW_MS = 15_000
    DECISION_SPAN_MS = 5_000
    INDOOR_ACCURACY_METERS = 20.0
    SCENE_STREAK_TO_FLIP = 2
    MAP_STREAK_TO_FLIP = 1
    NO_OUTDOOR_CUES_TO_FLIP = 4
    OUTDOOR_ACCURACY_METERS = 10.0
    SCENE_VOTE_WEIGHT = 2
    OUTDOOR_SCENE_STREAK_TO_RECOVER = 2
    CEILING_RECENT_MS = 5_000
    PURGE_WINDOW_MS = 10_000
    CAPTURE_DISCARD_DEBOUNCE_MS = 3_000

    def __init__(self) -> None:
        self._indoor_ts: deque[int] = deque()
        self._outdoor_ts: deque[int] = deque()
        self._last_scene_indoor_ts: int | None = None
        self._scene_indoor_streak = 0
        self._no_outdoor_cue_streak = 0
        self._inside_building_streak = 0
        self._last_indoor_accuracy_ts: int | None = None
        self._scene_outdoor_streak = 0
        self._last_ceiling_ts: int | None = None
        self.state = OutdoorState.UNCERTAIN
        self.capture_allowed = False
        self.last_indoor_flip_at_ms: int | None = None
        self._manual_override = False
        self.reasons: list[str] = []

    def force_outdoors(self) -> None:
        self._manual_override = True
        self._set_state(OutdoorState.OUTDOOR, 0)
        self.reasons.append("manual override")

    def _set_state(self, new_state: OutdoorState, now_ms: int) -> None:
        if new_state == OutdoorState.INDOOR and self.state != OutdoorState.INDOOR:
            self.last_indoor_flip_at_ms = now_ms
            self._scene_outdoor_streak = 0
        self.state = new_state
        self.capture_allowed = new_state == OutdoorState.OUTDOOR

    def on_accuracy_sample(self, horizontal_accuracy_meters: float | None, timestamp_ms: int) -> None:
        if horizontal_accuracy_meters is None:
            pass
        elif horizontal_accuracy_meters > self.INDOOR_ACCURACY_METERS:
            self._indoor_ts.append(timestamp_ms)
            self._last_indoor_accuracy_ts = timestamp_ms
        elif horizontal_accuracy_meters < self.OUTDOOR_ACCURACY_METERS:
            self._outdoor_ts.append(timestamp_ms)
        self._evaluate(timestamp_ms)

    def on_scene_vote(self, vote: SceneVote, timestamp_ms: int) -> None:
        if vote == SceneVote.INDOOR:
            for _ in range(self.SCENE_VOTE_WEIGHT):
                self._indoor_ts.append(timestamp_ms)
            self._last_scene_indoor_ts = timestamp_ms
            self._scene_indoor_streak += 1
            self._no_outdoor_cue_streak += 1
            self._scene_outdoor_streak = 0
        elif vote == SceneVote.OUTDOOR:
            for _ in range(self.SCENE_VOTE_WEIGHT):
                self._outdoor_ts.append(timestamp_ms)
            self._scene_indoor_streak = 0
            self._no_outdoor_cue_streak = 0
            self._scene_outdoor_streak += 1
            ceiling_recent = (
                self._last_ceiling_ts is not None
                and timestamp_ms - self._last_ceiling_ts <= self.CEILING_RECENT_MS
            )
            if (
                self.state != OutdoorState.OUTDOOR
                and self._scene_outdoor_streak >= self.OUTDOOR_SCENE_STREAK_TO_RECOVER
                and self._inside_building_streak == 0
                and not ceiling_recent
            ):
                self.reasons.append(f"camera outdoor ×{self._scene_outdoor_streak}")
                self._indoor_ts.clear()
                self._last_scene_indoor_ts = None
                self._last_indoor_accuracy_ts = None
                self._set_state(OutdoorState.OUTDOOR, timestamp_ms)
                return
        else:
            self._no_outdoor_cue_streak += 1
            self._scene_outdoor_streak = 0

        if self.state != OutdoorState.INDOOR:
            if self._scene_indoor_streak >= self.SCENE_STREAK_TO_FLIP:
                self.reasons.append(f"camera indoor ×{self._scene_indoor_streak}")
                if not self._manual_override:
                    self._set_state(OutdoorState.INDOOR, timestamp_ms)
                return
            if self._no_outdoor_cue_streak >= self.NO_OUTDOOR_CUES_TO_FLIP and not self._outdoor_ts:
                self.reasons.append(f"no outdoor cues ×{self._no_outdoor_cue_streak}")
                if not self._manual_override:
                    self._set_state(OutdoorState.INDOOR, timestamp_ms)
                return
        self._evaluate(timestamp_ms)

    def on_ceiling_plane(self, detected: bool, timestamp_ms: int) -> None:
        if detected:
            self._last_ceiling_ts = timestamp_ms
            for _ in range(self.SCENE_VOTE_WEIGHT):
                self._indoor_ts.append(timestamp_ms)
            if self.state != OutdoorState.INDOOR:
                self.reasons.append("ceiling plane")
                if self._manual_override:
                    self._manual_override = False
                    self.reasons.append("override cleared by ceiling")
                self._set_state(OutdoorState.INDOOR, timestamp_ms)
                return
        self._evaluate(timestamp_ms)

    def on_map_evidence(self, inside_building: bool, timestamp_ms: int) -> None:
        if inside_building:
            self._inside_building_streak += 1
            if self._manual_override:
                if self._inside_building_streak == self.MAP_STREAK_TO_FLIP:
                    self.reasons.append("map containment suppressed by override")
                return
            for _ in range(self.SCENE_VOTE_WEIGHT):
                self._indoor_ts.append(timestamp_ms)
            if (
                self._inside_building_streak >= self.MAP_STREAK_TO_FLIP
                and self.state != OutdoorState.INDOOR
            ):
                self.reasons.append("GPS inside mapped building")
                self._set_state(OutdoorState.INDOOR, timestamp_ms)
                return
        else:
            self._inside_building_streak = 0
        self._evaluate(timestamp_ms)

    def _evaluate(self, now_ms: int) -> None:
        self._prune(now_ms)
        scene_says_indoor = (
            self._last_scene_indoor_ts is not None
            and now_ms - self._last_scene_indoor_ts <= self.EVIDENCE_WINDOW_MS
        )
        gps_says_indoor = (
            self._last_indoor_accuracy_ts is not None
            and now_ms - self._last_indoor_accuracy_ts <= self.EVIDENCE_WINDOW_MS
        )
        if scene_says_indoor and gps_says_indoor:
            if self.state != OutdoorState.INDOOR:
                self.reasons.append("scene vote + degraded GPS")
            if not self._manual_override:
                self._set_state(OutdoorState.INDOOR, now_ms)
            return

        indoor_flip = (
            self._span(self._indoor_ts) >= self.DECISION_SPAN_MS
            and len(self._indoor_ts) > len(self._outdoor_ts)
        )
        outdoor_flip = self._span(self._outdoor_ts) >= self.DECISION_SPAN_MS and not any(
            ts >= self._outdoor_ts[0] for ts in self._indoor_ts
        )
        ceiling_recent = (
            self._last_ceiling_ts is not None
            and now_ms - self._last_ceiling_ts <= self.CEILING_RECENT_MS
        )
        if self.state == OutdoorState.INDOOR:
            if outdoor_flip and self._inside_building_streak == 0 and not ceiling_recent:
                self._scene_indoor_streak = 0
                self._no_outdoor_cue_streak = 0
                self._scene_outdoor_streak = 0
                new_state = OutdoorState.OUTDOOR
            else:
                new_state = OutdoorState.INDOOR
        elif indoor_flip and not self._manual_override:
            new_state = OutdoorState.INDOOR
        elif outdoor_flip:
            new_state = OutdoorState.OUTDOOR
        else:
            new_state = self.state
        if new_state != self.state:
            self.reasons.append(f"sustained {new_state}")
        self._set_state(new_state, now_ms)

    def _prune(self, now_ms: int) -> None:
        cutoff = now_ms - self.EVIDENCE_WINDOW_MS
        while self._indoor_ts and self._indoor_ts[0] < cutoff:
            self._indoor_ts.popleft()
        while self._outdoor_ts and self._outdoor_ts[0] < cutoff:
            self._outdoor_ts.popleft()

    @staticmethod
    def _span(entries: deque[int]) -> int:
        if len(entries) < 2:
            return 0
        return entries[-1] - entries[0]
