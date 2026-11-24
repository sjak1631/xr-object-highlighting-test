"""PC webcam client that speaks the same protocol as the Android XR app.

Use it to check the server and the label behaviour without the glasses:

    python webcam_demo.py --server ws://localhost:8765/ws --labels "cup, bottle, person"

Keys: q = quit, l = type new labels in the terminal.
"""

from __future__ import annotations

import argparse
import colorsys
import json
import struct
import time
import zlib

import cv2
from websockets.sync.client import connect


def label_color(label: str) -> tuple[int, int, int]:
    """Stable color per label (BGR); the Android app uses the same hash -> hue mapping."""
    hue = (zlib.crc32(label.encode("utf-8")) % 360) / 360.0
    r, g, b = colorsys.hsv_to_rgb(hue, 0.85, 1.0)
    return int(b * 255), int(g * 255), int(r * 255)


def draw_highlight(img, det: dict) -> None:
    h, w = img.shape[:2]
    x1, y1, x2, y2 = det["box"]
    p1 = (int(x1 * w), int(y1 * h))
    p2 = (int(x2 * w), int(y2 * h))
    color = label_color(det["label"])
    overlay = img.copy()
    cv2.rectangle(overlay, p1, p2, color, -1)
    cv2.addWeighted(overlay, 0.18, img, 0.82, 0, img)
    cv2.rectangle(img, p1, p2, color, 3)
    text = f'{det["label"]} {det["score"]:.2f}'
    cv2.putText(img, text, (p1[0] + 4, max(p1[1] - 8, 16)), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)


def send_config(ws, labels: list[str], conf: float) -> None:
    ws.send(json.dumps({"type": "config", "labels": labels, "conf": conf}))


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--server", default="ws://localhost:8765/ws")
    p.add_argument("--labels", default="person, cup, bottle")
    p.add_argument("--conf", type=float, default=0.25)
    p.add_argument("--camera", type=int, default=0)
    p.add_argument("--width", type=int, default=640)
    args = p.parse_args()

    labels = [l.strip() for l in args.labels.split(",") if l.strip()]
    cap = cv2.VideoCapture(args.camera)
    if not cap.isOpened():
        raise SystemExit(f"cannot open camera {args.camera}")

    with connect(args.server, max_size=None) as ws:
        send_config(ws, labels, args.conf)
        frame_id = 0
        last = time.perf_counter()
        fps = 0.0
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            scale = args.width / frame.shape[1]
            small = cv2.resize(frame, None, fx=scale, fy=scale) if scale < 1 else frame
            ok, jpg = cv2.imencode(".jpg", small, [cv2.IMWRITE_JPEG_QUALITY, 80])
            ws.send(struct.pack(">q", frame_id) + jpg.tobytes())

            # Stop-and-wait: read until we get the result for this frame.
            dets: list[dict] = []
            infer_ms = 0.0
            while True:
                msg = json.loads(ws.recv())
                if msg.get("type") == "detections" and msg.get("frame_id") == frame_id:
                    dets = msg["detections"]
                    infer_ms = msg["infer_ms"]
                    break
            frame_id += 1

            for d in dets:
                draw_highlight(frame, d)
            now = time.perf_counter()
            fps = 0.9 * fps + 0.1 * (1.0 / max(now - last, 1e-6))
            last = now
            cv2.putText(
                frame,
                f"{fps:4.1f} fps  infer {infer_ms:4.0f} ms  labels: {', '.join(labels)}",
                (10, 24),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.6,
                (255, 255, 255),
                2,
            )
            cv2.imshow("open-vocab highlight", frame)
            key = cv2.waitKey(1) & 0xFF
            if key == ord("q"):
                break
            if key == ord("l"):
                new = input("labels (comma separated): ")
                labels = [l.strip() for l in new.split(",") if l.strip()]
                send_config(ws, labels, args.conf)

    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
