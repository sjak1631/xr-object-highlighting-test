"""Open-vocabulary object detector wrapper.

Any text label (e.g. "cup", "red backpack", "person wearing glasses") can be
set at runtime. Backed by Ultralytics YOLO-World (default) or YOLOE.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass

import numpy as np

from aliases import to_model_label

log = logging.getLogger(__name__)


@dataclass
class Detection:
    label: str
    score: float
    # Normalized [0, 1] box in image coordinates (x right, y down).
    x1: float
    y1: float
    x2: float
    y2: float

    def to_dict(self) -> dict:
        return {
            "label": self.label,
            "score": round(self.score, 4),
            "box": [round(v, 5) for v in (self.x1, self.y1, self.x2, self.y2)],
        }


def _pick_device(requested: str | None) -> str:
    if requested:
        return requested
    try:
        import torch

        if torch.cuda.is_available():
            return "cuda:0"
        if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
            return "mps"
    except Exception:  # pragma: no cover - torch import problems
        pass
    return "cpu"


class OpenVocabDetector:
    """Thread-safe wrapper. `set_labels` is cheap if the labels did not change."""

    def __init__(
        self,
        model_name: str = "yolov8m-worldv2.pt",
        device: str | None = None,
        imgsz: int = 640,
    ) -> None:
        self.model_name = model_name
        self.device = _pick_device(device)
        self.imgsz = imgsz
        self._lock = threading.Lock()
        self._labels: tuple[str, ...] = ()
        self._is_yoloe = "yoloe" in model_name.lower()

        if self._is_yoloe:
            from ultralytics import YOLOE

            self.model = YOLOE(model_name)
        else:
            from ultralytics import YOLOWorld

            self.model = YOLOWorld(model_name)
        log.info("Loaded %s on %s", model_name, self.device)

    @property
    def labels(self) -> tuple[str, ...]:
        return self._labels

    def set_labels(self, labels: list[str]) -> None:
        cleaned = tuple(dict.fromkeys(l.strip() for l in labels if l and l.strip()))
        with self._lock:
            if cleaned == self._labels:
                return
            t0 = time.perf_counter()
            if cleaned:
                # The model sees English prompts; results are reported with the user's labels.
                names = [to_model_label(l) for l in cleaned]
                if self._is_yoloe:
                    self.model.set_classes(names, self.model.get_text_pe(names))
                else:
                    self.model.set_classes(names)
            self._labels = cleaned
            log.info(
                "Labels set to %s (%.0f ms)", list(cleaned), (time.perf_counter() - t0) * 1e3
            )

    def detect(self, image_bgr: np.ndarray, conf: float = 0.25, iou: float = 0.5) -> list[Detection]:
        with self._lock:
            if not self._labels:
                return []
            h, w = image_bgr.shape[:2]
            results = self.model.predict(
                image_bgr,
                imgsz=self.imgsz,
                conf=conf,
                iou=iou,
                device=self.device,
                verbose=False,
            )
            names = self._labels

        out: list[Detection] = []
        if not results:
            return out
        boxes = results[0].boxes
        if boxes is None or len(boxes) == 0:
            return out
        xyxy = boxes.xyxy.cpu().numpy()
        scores = boxes.conf.cpu().numpy()
        classes = boxes.cls.cpu().numpy().astype(int)
        for (x1, y1, x2, y2), s, c in zip(xyxy, scores, classes):
            label = names[c] if 0 <= c < len(names) else str(c)
            out.append(
                Detection(
                    label=label,
                    score=float(s),
                    x1=float(np.clip(x1 / w, 0, 1)),
                    y1=float(np.clip(y1 / h, 0, 1)),
                    x2=float(np.clip(x2 / w, 0, 1)),
                    y2=float(np.clip(y2 / h, 0, 1)),
                )
            )
        return out
