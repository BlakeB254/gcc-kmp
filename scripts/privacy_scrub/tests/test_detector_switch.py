"""SCRUB_DETECTOR switch: yolo (default, current live behaviour) | rfdetr.
Uses fake ultralytics / rfdetr modules — no GPU, no weights."""
import pathlib
import sys
import types

import numpy as np
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))


class _Box:
    def __init__(self, xyxy, cls, conf):
        self.xyxy = [types.SimpleNamespace(tolist=lambda: list(xyxy))]
        self.cls = [cls]
        self.conf = [conf]


class FakeYOLO:
    def __init__(self, weights):
        self.weights = weights
        self.names = {0: "person", 2: "car", 15: "cat"}

    def predict(self, frames, conf, imgsz, classes, device, verbose):
        boxes = [_Box((10, 20, 30, 40), 0, 0.9)] if "yolo11m" in self.weights else [_Box((1, 2, 3, 4), 0, 0.7)]
        return [types.SimpleNamespace(boxes=boxes) for _ in frames]


@pytest.fixture
def fake_backends(monkeypatch, tmp_path):
    monkeypatch.setitem(sys.modules, "ultralytics", types.SimpleNamespace(YOLO=FakeYOLO))
    rf = types.ModuleType("rfdetr")

    class FakeRF:
        def __init__(self, **kw):
            self.model = types.SimpleNamespace(resolution=704)

    rf.RFDETRLarge = rf.RFDETRMedium = FakeRF
    classes = types.ModuleType("rfdetr.assets.coco_classes")
    classes.COCO_CLASSES = {1: "person"}
    monkeypatch.setitem(sys.modules, "rfdetr", rf)
    monkeypatch.setitem(sys.modules, "rfdetr.assets", types.ModuleType("rfdetr.assets"))
    monkeypatch.setitem(sys.modules, "rfdetr.assets.coco_classes", classes)
    for name in ("detector", "detector_yolo"):
        sys.modules.pop(name, None)
    import detector_yolo  # noqa: PLC0415

    face = tmp_path / "face_yolov8m.pt"
    face.write_bytes(b"x")
    monkeypatch.setattr(detector_yolo, "FACE_WEIGHTS", face)
    monkeypatch.setattr(detector_yolo.PrivacyDetector.__init__, "__defaults__",
                        (detector_yolo.COCO_WEIGHTS, face, 0.25, 1280, None))
    yield
    for name in ("detector", "detector_yolo"):
        sys.modules.pop(name, None)


def test_default_is_yolo_the_current_live_detector(fake_backends):
    import scrub_worker as sw  # noqa: PLC0415

    det = sw.load_detector({})
    assert type(det).__module__ == "detector_yolo"
    assert det.describe() == {"coco": "yolo11m.pt", "face": "face_yolov8m.pt", "conf": 0.25, "imgsz": 1280}
    out = det.detect_frames([np.zeros((8, 8, 3), np.uint8)])
    assert sorted(d.label for d in out[0]) == ["face", "person"]
    assert det.qc_counts([np.zeros((8, 8, 3), np.uint8)]) == {"person": 1}


def test_rfdetr_when_switched(fake_backends, monkeypatch):
    import scrub_worker as sw  # noqa: PLC0415

    monkeypatch.setattr(sys.modules["cv2"], "FaceDetectorYN",
                        types.SimpleNamespace(create=lambda *a, **k: object()), raising=False)
    monkeypatch.delitem(sys.modules, "ultralytics")
    det = sw.load_detector({"SCRUB_DETECTOR": "rfdetr"})
    assert type(det).__module__ == "detector"
    assert det.describe()["objects"] == "rf-detr-large"
    assert "ultralytics" not in sys.modules  # the AGPL backend is never imported


def test_unknown_detector_is_refused(fake_backends):
    import scrub_worker as sw  # noqa: PLC0415

    with pytest.raises(SystemExit):
        sw.load_detector({"SCRUB_DETECTOR": "xl"})


def test_rfdetr_install_carries_no_agpl():
    here = pathlib.Path(__file__).resolve().parents[1]

    def resolved(name):
        lines = []
        for line in (here / name).read_text().splitlines():
            line = line.split("#", 1)[0].strip()
            if line.startswith("-r "):
                lines += resolved(line[3:].strip())
            elif line:
                lines.append(line.lower())
        return lines

    rf = resolved("requirements-rfdetr.txt")
    assert any(r.startswith("rfdetr") for r in rf)
    assert not any("ultralytics" in r for r in rf)
    assert any("ultralytics" in r for r in resolved("requirements-yolo.txt"))
    assert not (here / "requirements.txt").exists()
