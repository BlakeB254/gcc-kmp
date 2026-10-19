"""SCRUB_DETECTOR=rfdetr — the permissively licensed replacement for
detector_yolo.py (Ultralytics, AGPL). Not live until the rotated-face sweep
passes (README "Detector switch").

Privacy detector for scan chunks: one RF-DETR pass (COCO person + vehicle
classes) + one YuNet face pass (belt-and-braces for partially-visible
people). Whole-person and whole-vehicle boxes get blurred downstream, which
covers faces and license plates by construction.

Licences (no AGPL in this path):
  rfdetr package + RF-DETR Nano/Small/Medium/Large COCO weights  Apache-2.0
    (XL/2XL are under Roboflow's PML — never use them here)
  OpenCV FaceDetectorYN (Apache-2.0) + face_detection_yunet_2023mar.onnx
    (opencv_zoo, MIT)

Weights live in the central model repo (~/models), never in the project tree
(setup.sh fetches them). GB10: torch cu130 venv (see setup.sh).
"""
from __future__ import annotations

import os
from pathlib import Path

import cv2
import numpy as np

from scrub_video import Detection

MODELS_DIR = Path(os.environ.get(
    "SCRUB_MODELS_DIR", "/home/codex450/models/specialty/detection"
))
FACE_WEIGHTS = MODELS_DIR / "face_detection_yunet_2023mar.onnx"
# Apache-2.0 variants only. Large by default: privacy errs toward recall, and
# Large is the strongest licensed variant (benchmark_detectors.py, README).
RFDETR_VARIANTS = {
    "medium": ("RFDETRMedium", "rf-detr-medium.pth"),
    "large": ("RFDETRLarge", "rf-detr-large-2026.pth"),
}
DEFAULT_VARIANT = os.environ.get("SCRUB_RFDETR_VARIANT", "large")
PRIVACY_CLASS_NAMES = frozenset({"person", "bicycle", "car", "motorcycle", "bus", "truck"})


class PrivacyDetector:
    def __init__(
        self,
        variant: str = DEFAULT_VARIANT,
        face_weights: Path | None = FACE_WEIGHTS,
        conf: float = 0.25,
        # 0.3, not YuNet's usual 0.6/0.5: privacy errs toward over-blurring
        # (the 2026-10-18 benchmark found YuNet at 0.5 missing most of the
        # faces YOLO-face found — README).
        face_conf: float = 0.3,
        imgsz: int | None = None,
        device: str | None = None,
        face_scale: float = 1.0,
        face_rotations: tuple[int, ...] = (0, 90, 270),
    ):
        import rfdetr  # noqa: PLC0415 — torch import is slow; keep --dry-run instant
        from rfdetr.assets.coco_classes import COCO_CLASSES  # noqa: PLC0415

        if variant not in RFDETR_VARIANTS:
            raise ValueError(f"unknown/unlicensed RF-DETR variant {variant!r}")
        cls_name, weights = RFDETR_VARIANTS[variant]
        kwargs = {"pretrain_weights": str(MODELS_DIR / weights)}
        if device:
            kwargs["device"] = device
        self.model = getattr(rfdetr, cls_name)(**kwargs)
        self.class_names = COCO_CLASSES
        self.variant, self.conf, self.face_conf = variant, conf, face_conf
        self.face_scale, self.face_rotations = face_scale, tuple(face_rotations)
        # Inference resolution (square, multiple of 32); None = the variant's
        # training resolution.
        self.imgsz = imgsz or self.model.model.resolution
        self.face = None
        if face_weights and Path(face_weights).exists():
            self.face = cv2.FaceDetectorYN.create(
                str(face_weights), "", (320, 320),
                score_threshold=face_conf, nms_threshold=0.3, top_k=5000,
            )

    def _objects(self, frames: list[np.ndarray], conf: float) -> list[list[Detection]]:
        rgb = [cv2.cvtColor(f, cv2.COLOR_BGR2RGB) for f in frames]
        preds = self.model.predict(
            rgb, threshold=conf, shape=(self.imgsz, self.imgsz), include_source_image=False,
        )
        if not isinstance(preds, list):
            preds = [preds]
        out = []
        for det in preds:
            dets = []
            for xyxy, cls_id, score in zip(det.xyxy, det.class_id, det.confidence):
                label = self.class_names.get(int(cls_id))
                if label in PRIVACY_CLASS_NAMES:
                    x1, y1, x2, y2 = (int(v) for v in xyxy)
                    dets.append(Detection((x1, y1, x2, y2), label, float(score)))
            out.append(dets)
        return out

    def _faces_once(self, img: np.ndarray) -> list[tuple[float, float, float, float, float]]:
        h, w = img.shape[:2]
        if self.face_scale != 1.0:
            img = cv2.resize(img, (int(w * self.face_scale), int(h * self.face_scale)),
                             interpolation=cv2.INTER_LINEAR)
        self.face.setInputSize((img.shape[1], img.shape[0]))
        _, faces = self.face.detect(img)
        s = self.face_scale
        return [(f[0] / s, f[1] / s, (f[0] + f[2]) / s, (f[1] + f[3]) / s, float(f[-1]))
                for f in (faces if faces is not None else [])]

    def _faces(self, frame: np.ndarray) -> list[Detection]:
        """YuNet is not rotation-invariant and capture chunks decode sideways
        (the phone's rotation flag is not applied), so look upright and at
        both quarter turns, mapping boxes back to frame coordinates."""
        h, w = frame.shape[:2]
        boxes = []
        for rot in self.face_rotations:
            if rot == 0:
                boxes += self._faces_once(frame)
            elif rot == 90:  # clockwise: x' = h-1-y, y' = x
                for x1, y1, x2, y2, s in self._faces_once(cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)):
                    boxes.append((y1, h - x2, y2, h - x1, s))
            elif rot == 270:  # counter-clockwise: x' = y, y' = w-1-x
                for x1, y1, x2, y2, s in self._faces_once(cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)):
                    boxes.append((w - y2, x1, w - y1, x2, s))
        return [
            Detection((max(0, int(x1)), max(0, int(y1)), min(w, int(x2 + 0.5)), min(h, int(y2 + 0.5))),
                      "face", s)
            for x1, y1, x2, y2, s in boxes
        ]

    def detect_frames(self, frames: list[np.ndarray]) -> list[list[Detection]]:
        merged = self._objects(frames, self.conf)
        if self.face is not None:
            for dets, frame in zip(merged, frames):
                dets.extend(self._faces(frame))
        return merged

    def qc_counts(self, frames: list[np.ndarray], conf: float = 0.4) -> dict[str, int]:
        """Residual person/vehicle detections on (scrubbed) frames — the
        post-scrub QC signal: near-zero means the blur destroyed what the
        detector keyed on."""
        counts: dict[str, int] = {}
        if not frames:
            return counts
        for dets in self._objects(frames, conf):
            for d in dets:
                counts[d.label] = counts.get(d.label, 0) + 1
        return counts

    def describe(self) -> dict:
        """Provenance for scrub-report.json."""
        return {
            "objects": f"rf-detr-{self.variant}",
            "face": "yunet-2023mar" if self.face is not None else None,
            "conf": self.conf, "faceConf": self.face_conf, "imgsz": self.imgsz,
            "faceScale": self.face_scale, "faceRotations": list(self.face_rotations),
        }
