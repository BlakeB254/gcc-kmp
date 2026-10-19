"""PrivacyDetector wiring with a fake rfdetr module — no GPU, no weights.
Checks the label filter, BGR→RGB hand-off, face merge and report block."""
import pathlib
import sys
import types

import numpy as np
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

COCO = {1: "person", 2: "bicycle", 3: "car", 4: "motorcycle", 6: "bus", 8: "truck",
        15: "bench", 62: "chair"}


class FakeDetections:
    def __init__(self, rows):
        self.xyxy = np.array([r[0] for r in rows], dtype=float).reshape(-1, 4)
        self.class_id = np.array([r[1] for r in rows], dtype=int)
        self.confidence = np.array([r[2] for r in rows], dtype=float)


class FakeModel:
    calls: list = []

    def __init__(self, **kwargs):
        self.kwargs = kwargs
        self.model = types.SimpleNamespace(resolution=704)

    def predict(self, images, threshold, shape, include_source_image):
        FakeModel.calls.append((images, threshold, shape))
        rows = [((10, 20, 110, 220), 1, 0.9), ((0, 0, 50, 50), 15, 0.95),
                ((200, 100, 400, 200), 3, 0.3)]
        out = [FakeDetections([r for r in rows if r[2] >= threshold]) for _ in images]
        return out if len(out) > 1 else out[0]


class FakeFace:
    """Finds the single bright pixel block of the image it is given — so a
    face drawn in the frame is 'detected' at whatever orientation it is
    shown, and the test can check the box maps back to frame coordinates."""

    upright_only = True

    def setInputSize(self, size):
        self.size = size

    def setScoreThreshold(self, t):
        pass

    def detect(self, img):
        assert (img.shape[1], img.shape[0]) == self.size
        ys, xs = np.nonzero(img[..., 1] == 200)
        if not len(xs) or (self.upright_only and img.shape[1] < img.shape[0]):
            return 1, None
        x1, y1, x2, y2 = xs.min(), ys.min(), xs.max() + 1, ys.max() + 1
        return 1, np.array([[x1, y1, x2 - x1, y2 - y1] + [0] * 10 + [0.8]], dtype=np.float32)


@pytest.fixture
def detector(monkeypatch, tmp_path):
    fake = types.ModuleType("rfdetr")
    fake.RFDETRLarge = FakeModel
    fake.RFDETRMedium = FakeModel
    assets = types.ModuleType("rfdetr.assets")
    classes = types.ModuleType("rfdetr.assets.coco_classes")
    classes.COCO_CLASSES = COCO
    monkeypatch.setitem(sys.modules, "rfdetr", fake)
    monkeypatch.setitem(sys.modules, "rfdetr.assets", assets)
    monkeypatch.setitem(sys.modules, "rfdetr.assets.coco_classes", classes)
    import detector as det_mod  # noqa: PLC0415

    face_path = tmp_path / "yunet.onnx"
    face_path.write_bytes(b"x")
    monkeypatch.setattr(det_mod.cv2, "FaceDetectorYN",
                        types.SimpleNamespace(create=lambda *a, **k: FakeFace()))
    FakeModel.calls = []
    return det_mod.PrivacyDetector(variant="large", face_weights=face_path)


def _frame_with_face():
    frame = np.zeros((480, 640, 3), dtype=np.uint8)
    frame[..., 0] = 255  # blue in BGR
    frame[40:65, 30:50, 1] = 200  # the "face"
    return frame


def test_keeps_only_privacy_classes_and_adds_faces(detector):
    frame = _frame_with_face()
    out = detector.detect_frames([frame, frame])
    assert len(out) == 2
    labels = sorted(d.label for d in out[0])
    assert labels == ["car", "face", "person"]  # bench dropped; face found upright only
    face = next(d for d in out[0] if d.label == "face")
    assert face.box == (30, 40, 50, 65)
    images, threshold, shape = FakeModel.calls[0]
    assert threshold == 0.25 and shape == (704, 704)
    assert images[0][0, 0, 2] == 255  # handed over as RGB


@pytest.mark.parametrize("rots", [(90,), (270,)])
def test_rotated_face_boxes_map_back_to_the_frame(detector, rots):
    FakeFace.upright_only = False
    try:
        detector.face_rotations = rots
        faces = detector._faces(_frame_with_face())
    finally:
        FakeFace.upright_only = True
    assert [f.box for f in faces] == [(30, 40, 50, 65)]


def test_face_upscale_maps_back(detector):
    detector.face_scale = 2.0
    faces = detector._faces(_frame_with_face())
    x1, y1, x2, y2 = faces[0].box
    assert abs(x1 - 30) <= 1 and abs(y1 - 40) <= 1 and abs(x2 - 50) <= 1 and abs(y2 - 65) <= 1


def test_qc_counts_uses_its_own_threshold(detector):
    frame = np.zeros((480, 640, 3), dtype=np.uint8)
    assert detector.qc_counts([frame]) == {"person": 1}  # car at 0.3 < 0.4
    assert detector.qc_counts([]) == {}


def test_rejects_unlicensed_variants(detector):
    import detector as det_mod  # noqa: PLC0415

    with pytest.raises(ValueError):
        det_mod.PrivacyDetector(variant="xlarge")


def test_describe_names_the_models(detector):
    d = detector.describe()
    assert d["objects"] == "rf-detr-large" and d["face"] == "yunet-2023mar"
    assert d["imgsz"] == 704
