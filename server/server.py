"""WebSocket inference server for the XREAL Aura highlighter.

Protocol (one WebSocket connection per client, path /ws):

client -> server
  text   {"type": "config", "labels": ["cup", "bottle"], "conf": 0.25}
  binary 8-byte big-endian frame id + JPEG bytes, or
         8-byte frame id + 4-byte header length + JSON header + JPEG, where the header is
         {"ts": ns, "q": [x,y,z,w], "p": [x,y,z], "w": [wx,wy,wz]} = head pose at exposure
         (perception/world frame) and world angular velocity; enables auto calibration
  text   {"type": "calib_reset"}

server -> client
  text   {"type": "config_ack", "labels": [...]}
  text   {"type": "detections", "frame_id": 123, "infer_ms": 18.2,
          "width": 640, "height": 480,
          "detections": [{"label": "cup", "score": 0.81, "box": [x1, y1, x2, y2]}]}
  text   {"type": "calibration", "q": [...], "tan_h": .., "tan_v": .., "cx": .., "cy": ..,
          "dt_ms": .., "rms_px": .., "pairs": .., "quality": .., "converged": true}
          (see calibration.py)

Boxes are normalized to [0, 1]. The client is expected to send the next frame
only after receiving the result of the previous one (stop-and-wait), which
keeps latency bounded on slow networks.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import struct
import time

import cv2
import numpy as np
import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from calibration import AutoCalibrator
from detector import OpenVocabDetector

log = logging.getLogger("server")


def parse_frame(payload: bytes) -> tuple[int, dict | None, bytes]:
    """[int64 frame_id][JPEG]  or  [int64 frame_id][uint32 header_len][JSON header][JPEG]."""
    (frame_id,) = struct.unpack(">q", payload[:8])
    if payload[8:10] == bytes((0xFF, 0xD8)):  # JPEG SOI right after the id: no header
        return frame_id, None, payload[8:]
    (hlen,) = struct.unpack(">I", payload[8:12])
    header = json.loads(payload[12 : 12 + hlen].decode("utf-8"))
    return frame_id, header, payload[12 + hlen :]


def build_app(detector: OpenVocabDetector, default_labels: list[str]) -> FastAPI:
    app = FastAPI(title="XR object highlighter")
    if default_labels:
        detector.set_labels(default_labels)

    @app.get("/health")
    def health() -> dict:
        return {
            "ok": True,
            "model": detector.model_name,
            "device": detector.device,
            "labels": list(detector.labels),
        }

    @app.websocket("/ws")
    async def ws_endpoint(ws: WebSocket) -> None:
        await ws.accept()
        peer = f"{ws.client.host}:{ws.client.port}" if ws.client else "?"
        log.info("client connected: %s", peer)
        conf = 0.25
        labels: list[str] = list(detector.labels)
        loop = asyncio.get_running_loop()
        calibrator = AutoCalibrator()
        try:
            while True:
                msg = await ws.receive()
                if msg.get("type") == "websocket.disconnect":
                    break
                if (text := msg.get("text")) is not None:
                    data = json.loads(text)
                    if data.get("type") == "config":
                        labels = [str(l) for l in data.get("labels", [])]
                        conf = float(data.get("conf", conf))
                        await loop.run_in_executor(None, detector.set_labels, labels)
                        await ws.send_text(
                            json.dumps({"type": "config_ack", "labels": list(detector.labels)})
                        )
                    elif data.get("type") == "calib_reset":
                        calibrator.reset()
                        log.info("calibration reset by client")
                    continue

                payload = msg.get("bytes")
                if not payload or len(payload) < 9:
                    continue
                frame_id, header, jpeg = parse_frame(payload)
                img = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
                if img is None:
                    continue
                # Several clients may share one detector with different labels.
                if tuple(labels) != detector.labels and labels:
                    await loop.run_in_executor(None, detector.set_labels, labels)
                t0 = time.perf_counter()
                dets = await loop.run_in_executor(None, detector.detect, img, conf)
                infer_ms = (time.perf_counter() - t0) * 1e3
                await ws.send_text(
                    json.dumps(
                        {
                            "type": "detections",
                            "frame_id": frame_id,
                            "infer_ms": round(infer_ms, 1),
                            "width": int(img.shape[1]),
                            "height": int(img.shape[0]),
                            "detections": [d.to_dict() for d in dets],
                        }
                    )
                )
                # Auto calibration runs on the same frames (after the result was sent).
                if header and "q" in header:
                    await loop.run_in_executor(None, calibrator.add_frame, img, header)
                if (calib := calibrator.pop_result()) is not None:
                    await ws.send_text(json.dumps(calib))
        except WebSocketDisconnect:
            pass
        finally:
            log.info("client disconnected: %s", peer)

    return app


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument(
        "--model",
        default="yolov8m-worldv2.pt",
        help="yolov8{s,m,l,x}-worldv2.pt (YOLO-World) or yoloe-11{s,m,l}-seg.pt (YOLOE)",
    )
    p.add_argument("--device", default=None, help="cuda:0 / mps / cpu (auto if omitted)")
    p.add_argument("--imgsz", type=int, default=640)
    p.add_argument("--labels", default="", help="initial comma-separated labels")
    args = p.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    detector = OpenVocabDetector(args.model, args.device, args.imgsz)
    default_labels = [l for l in args.labels.split(",") if l.strip()]
    uvicorn.run(build_app(detector, default_labels), host=args.host, port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
