"""SCRUB_DETECTOR=yolo (the default until the RF-DETR face sweep passes — see
README "Detector switch"). Ultralytics is AGPL-3.0: internal use only, and
this path is retired once the switch flips to rfdetr.

YOLO-based privacy detector for scan chunks: one COCO pass (person +
vehicle classes) + one face pass (belt-and-braces for partially-visible
people). Whole-person and whole-vehicle boxes get blurred downstream, which
covers faces and license plates by construction. Weights live in the central
model repo (~/models), never in the project tree. GB10: torch nightly venv
(see setup.sh)."""
from __future__ import annotations

from pathlib import Path

import numpy as np
from ultralytics import YOLO

from scrub_video import Detection

# ~/models/ultralytics is root-owned (read-only) — new weights go in the
# writable specialty/detection tree; the face model predates that split.
COCO_WEIGHTS = Path("/home/codex450/models/specialty/detection/yolo11m.pt")
FACE_WEIGHTS = Path("/home/codex450/models/ultralytics/bbox/face_yolov8m.pt")
PRIVACY_COCO_CLASSES = [0, 1, 2, 3, 5, 7]  # person bicycle car motorcycle bus truck


class PrivacyDetector:
    def __init__(
        self,
        coco_weights: Path = COCO_WEIGHTS,
        face_weights: Path | None = FACE_WEIGHTS,
        conf: float = 0.25,
        imgsz: int = 1280,
        device: str | None = None,
    ):
        self.conf, self.imgsz, self.device = conf, imgsz, device
        self.coco = YOLO(str(coco_weights))
        self.face = YOLO(str(face_weights)) if face_weights and Path(face_weights).exists() else None

    def _run(self, model, frames, classes=None, label_override=None):
        results = model.predict(
            frames, conf=self.conf, imgsz=self.imgsz, classes=classes,
            device=self.device, verbose=False,
        )
        out = []
        for r in results:
            dets = []
            for b in r.boxes:
                x1, y1, x2, y2 = (int(v) for v in b.xyxy[0].tolist())
                label = label_override or model.names[int(b.cls[0])]
                dets.append(Detection((x1, y1, x2, y2), label, float(b.conf[0])))
            out.append(dets)
        return out

    def detect_frames(self, frames: list[np.ndarray]) -> list[list[Detection]]:
        merged = self._run(self.coco, frames, classes=PRIVACY_COCO_CLASSES)
        if self.face is not None:
            for dets, faces in zip(merged, self._run(self.face, frames, label_override="face")):
                dets.extend(faces)
        return merged

    def describe(self) -> dict:
        """Provenance for scrub-report.json (same keys the report had before)."""
        return {"coco": "yolo11m.pt", "face": "face_yolov8m.pt",
                "conf": self.conf, "imgsz": self.imgsz}

    def qc_counts(self, frames: list[np.ndarray], conf: float = 0.4) -> dict[str, int]:
        """Residual person/vehicle detections on (scrubbed) frames — the
        post-scrub QC signal: near-zero means the blur destroyed what the
        detector keyed on."""
        counts: dict[str, int] = {}
        if not frames:
            return counts
        for r in self.coco.predict(
            frames, conf=conf, imgsz=self.imgsz, classes=PRIVACY_COCO_CLASSES,
            device=self.device, verbose=False,
        ):
            for b in r.boxes:
                name = self.coco.names[int(b.cls[0])]
                counts[name] = counts.get(name, 0) + 1
        return counts
