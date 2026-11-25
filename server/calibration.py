"""Automatic camera calibration from head tracking + image motion.

The XR runtime tells us how the head rotated between two frames; the images tell us
how the scene moved in pixels. For (mostly) rotational head motion the two frames are
related by a homography

    p2 ~ K * S * C2^T * C1 * S * K^-1 * p1,     C_i = R_head_i(t_i + dt) * X

so by matching ORB features between frames we can estimate, without any target:

  * K   : focal length and principal point of the image that is actually sent
  * X   : rotation of the camera relative to the tracked head pose
  * dt  : offset between image timestamps and head pose timestamps

(S flips between the OpenCV camera convention, y down / z forward, and the OpenXR one,
y up / -z forward.) The user only has to look around a bit; this runs continuously in
the background on frames that are being sent for detection anyway.
"""

from __future__ import annotations

import logging
import threading
from collections import deque
from dataclasses import dataclass

import cv2
import numpy as np
from scipy.optimize import least_squares
from scipy.spatial.transform import Rotation

log = logging.getLogger(__name__)

S = np.diag([1.0, -1.0, -1.0])
TAN_HALF_REF = 0.6  # reference tan(hfov/2) used to parametrize the focal length


def distort(xn: np.ndarray, k1: float, k2: float) -> np.ndarray:
    r2 = np.sum(xn * xn, axis=1, keepdims=True)
    return xn * (1 + k1 * r2 + k2 * r2 * r2)


def undistort(xd: np.ndarray, k1: float, k2: float, iters: int = 6) -> np.ndarray:
    xu = xd.copy()
    for _ in range(iters):
        r2 = np.sum(xu * xu, axis=1, keepdims=True)
        xu = xd / (1 + k1 * r2 + k2 * r2 * r2)
    return xu


@dataclass
class _Frame:
    pts: np.ndarray
    des: np.ndarray
    rot: Rotation  # head -> world
    pos: np.ndarray
    angvel: np.ndarray  # world frame, rad/s
    size: tuple[int, int]


@dataclass
class _Pair:
    p1: np.ndarray
    p2: np.ndarray
    r1: Rotation
    r2: Rotation
    w1: np.ndarray
    w2: np.ndarray
    size: tuple[int, int]


class AutoCalibrator:
    MIN_PAIRS = 20
    MIN_ROT_DEG = 3.0
    MAX_ROT_DEG = 30.0
    MAX_TRANS_M = 0.02  # translation causes parallax, which a homography cannot explain
    PTS_PER_PAIR = 40

    def __init__(self) -> None:
        self._orb = cv2.ORB_create(nfeatures=700, fastThreshold=12)
        self._bf = cv2.BFMatcher(cv2.NORM_HAMMING)
        self._lock = threading.Lock()
        self._rng = np.random.default_rng(0)
        self.reset()

    def reset(self) -> None:
        with getattr(self, "_lock", threading.Lock()):
            self._keyframes: deque[_Frame] = deque(maxlen=15)
            self._pairs: deque[_Pair] = deque(maxlen=200)
            self._new_pairs = 0
            self._solving = False
            self._pending: dict | None = None
            self._frame_count = 0
            self._last_x: np.ndarray | None = None
            self._prev_solution: tuple[np.ndarray, float] | None = None
            self._stable_count = 0
            self.result: dict | None = None

    # ------------------------------------------------------------------ input

    def add_frame(self, img_bgr: np.ndarray, pose: dict) -> None:
        """pose: {"q": [x, y, z, w], "p": [x, y, z], "w": [wx, wy, wz]} (head, world frame)."""
        self._frame_count += 1
        if self.result and self.result.get("converged"):
            return  # frozen once converged (client can send calib_reset to start over)
        if self._frame_count % 2:
            return  # every other frame is plenty and keeps CPU free for detection

        gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
        kp, des = self._orb.detectAndCompute(gray, None)
        if des is None or len(kp) < 80:
            return
        frame = _Frame(
            pts=np.float32([k.pt for k in kp]),
            des=des,
            rot=Rotation.from_quat(pose["q"]),
            pos=np.asarray(pose.get("p", [0, 0, 0]), float),
            angvel=np.asarray(pose.get("w", [0, 0, 0]), float),
            size=(gray.shape[1], gray.shape[0]),
        )

        for kf in self._keyframes:
            if kf.size != frame.size:
                continue
            rel_deg = np.degrees((kf.rot.inv() * frame.rot).magnitude())
            if not (self.MIN_ROT_DEG <= rel_deg <= self.MAX_ROT_DEG):
                continue
            if np.linalg.norm(kf.pos - frame.pos) > self.MAX_TRANS_M:
                continue
            pair = self._match(kf, frame)
            if pair is not None:
                with self._lock:
                    self._pairs.append(pair)
                    self._new_pairs += 1

        if not self._keyframes or np.degrees(
            (self._keyframes[-1].rot.inv() * frame.rot).magnitude()
        ) > 2.5:
            self._keyframes.append(frame)

        if self._new_pairs >= 12 and len(self._pairs) >= 12 and not self._solving:
            self._solving = True
            threading.Thread(target=self._solve_bg, daemon=True).start()

    def pop_result(self) -> dict | None:
        """Returns a new calibration result once (non-blocking)."""
        with self._lock:
            r, self._pending = self._pending, None
            return r

    # ---------------------------------------------------------------- matching

    def _match(self, a: _Frame, b: _Frame) -> _Pair | None:
        knn = self._bf.knnMatch(a.des, b.des, k=2)
        good = [m[0] for m in knn if len(m) == 2 and m[0].distance < 0.8 * m[1].distance]
        if len(good) < 40:
            return None
        p1 = a.pts[[m.queryIdx for m in good]]
        p2 = b.pts[[m.trainIdx for m in good]]
        _, mask = cv2.findHomography(p1, p2, cv2.RANSAC, 3.0)
        if mask is None:
            return None
        inl = mask.ravel().astype(bool)
        if inl.sum() < 30:
            return None
        p1, p2 = p1[inl], p2[inl]
        if len(p1) > self.PTS_PER_PAIR:
            idx = self._rng.choice(len(p1), self.PTS_PER_PAIR, replace=False)
            p1, p2 = p1[idx], p2[idx]
        return _Pair(p1.astype(float), p2.astype(float), a.rot, b.rot, a.angvel, b.angvel, a.size)

    # ------------------------------------------------------------------ solve

    def _solve_bg(self) -> None:
        try:
            with self._lock:
                pairs = list(self._pairs)
                self._new_pairs = 0
            res = self.solve(pairs)
            if res is not None:
                with self._lock:
                    self.result = res
                    self._pending = res
                log.info(
                    "calibration: hfov=%.1f vfov=%.1f rot=%s dt=%.0fms k=(%.3f,%.3f) rms=%.2fpx pairs=%d q=%.2f conv=%s",
                    res["hfov_deg"], res["vfov_deg"], res["euler_deg"], res["dt_ms"], res["k1"], res["k2"],
                    res["rms_px"], res["pairs"], res["quality"], res["converged"],
                )
        except Exception:
            log.exception("calibration solve failed")
        finally:
            self._solving = False

    @staticmethod
    def _pack(pairs: list[_Pair]) -> dict:
        """Stack all pairs into arrays so residuals can be evaluated without Python loops."""
        return {
            "R1": np.stack([p.r1.as_matrix() for p in pairs]),
            "R2": np.stack([p.r2.as_matrix() for p in pairs]),
            "w1": np.stack([p.w1 for p in pairs]),
            "w2": np.stack([p.w2 for p in pairs]),
            "idx": np.concatenate([np.full(len(p.p1), i) for i, p in enumerate(pairs)]),
            "p1h": np.c_[np.concatenate([p.p1 for p in pairs]), np.ones(sum(len(p.p1) for p in pairs))],
            "p2": np.concatenate([p.p2 for p in pairs]),
        }

    @staticmethod
    def _residuals(x: np.ndarray, d: dict, W: int, H: int) -> np.ndarray:
        X = Rotation.from_rotvec(x[:3]).as_matrix()
        f = W / (2 * TAN_HALF_REF) * np.exp(x[3])
        cx = W / 2 + x[4] * W
        cy = H / 2 + x[5] * H
        dt = x[6]
        K = np.array([[f, 0, cx], [0, f, cy], [0, 0, 1.0]])
        Kinv = np.array([[1 / f, 0, -cx / f], [0, 1 / f, -cy / f], [0, 0, 1.0]])
        C1 = Rotation.from_rotvec(d["w1"] * dt).as_matrix() @ d["R1"] @ X
        C2 = Rotation.from_rotvec(d["w2"] * dt).as_matrix() @ d["R2"] @ X
        k1, k2 = x[7], x[8]
        M = S @ np.transpose(C2, (0, 2, 1)) @ C1 @ S  # camera1 ray -> camera2 ray (OpenCV axes)
        xd = d["p1h"] @ Kinv.T  # distorted normalized coordinates
        xu = undistort(xd[:, :2], k1, k2)
        ray2 = np.einsum("nij,nj->ni", M[d["idx"]], np.c_[xu, np.ones(len(xu))])
        z = ray2[:, 2:3]
        z = np.where(np.abs(z) < 1e-6, 1e-6, z)
        x2 = distort(ray2[:, :2] / z, k1, k2)
        pred = x2 * f + np.array([cx, cy])
        res = (pred - d["p2"]).ravel()
        # Weak priors keep unobservable directions (e.g. no roll motion) near sane values.
        prior = np.r_[x[:3] / 0.35, x[3] / 0.7, x[4:6] / 0.15, x[6] / 0.1, x[7:9] / 0.3]
        return np.r_[res, prior]

    def solve(self, pairs: list[_Pair]) -> dict | None:
        if not pairs:
            return None
        W, H = pairs[-1].size
        pairs = [p for p in pairs if p.size == (W, H)]
        data = self._pack(pairs)
        if self._last_x is not None:
            inits = [self._last_x]  # warm start: refinement is fast
        else:
            inits = []
            for tan_init in (0.45, 0.6, 0.8):
                x0 = np.zeros(9)
                x0[3] = np.log(TAN_HALF_REF / tan_init)
                inits.append(x0)
        best = None
        for x0 in inits:
            sol = least_squares(
                self._residuals, x0, args=(data, W, H), loss="huber", f_scale=3.0,
                x_scale=np.array([0.05, 0.05, 0.05, 0.05, 0.02, 0.02, 0.02, 0.05, 0.05]), max_nfev=200,
            )
            if best is None or sol.cost < best.cost:
                best = sol
        x = best.x
        self._last_x = x.copy()
        n_pts = sum(len(p.p1) for p in pairs)
        r = self._residuals(x, data, W, H)[: 2 * n_pts].reshape(-1, 2)
        err = np.linalg.norm(r, axis=1)
        rms = float(np.sqrt(np.mean(np.minimum(err, 10.0) ** 2)))
        inlier_ratio = float(np.mean(err < 3.0))

        # Observability: need head rotations about at least two different axes.
        A = np.zeros((3, 3))
        for p in pairs:
            rv = (p.r1.inv() * p.r2).as_rotvec()
            A += np.outer(rv, rv)
        eig = np.sort(np.linalg.eigvalsh(A))
        quality = float(np.sqrt(max(eig[1], 0) / max(eig[2], 1e-12)))

        f = W / (2 * TAN_HALF_REF) * np.exp(x[3])
        X = Rotation.from_rotvec(x[:3])
        # Same convention as androidx Quaternion.fromEulerAngles: YXZ = (yaw, pitch, roll)
        yaw, pitch, roll = X.as_euler("YXZ", degrees=True)
        tan_h, tan_v = W / (2 * f), H / (2 * f)
        # Stable = agrees with the previous solve (different, larger data set).
        stable = False
        if self._prev_solution is not None:
            px, pf = self._prev_solution
            drot = np.degrees((Rotation.from_rotvec(px[:3]).inv() * X).magnitude())
            stable = drot < 1.0 and abs(f / pf - 1) < 0.015 and abs(x[6] - px[6]) < 0.01
        self._prev_solution = (x.copy(), f)
        self._stable_count = self._stable_count + 1 if stable else 0
        converged = (
            len(pairs) >= self.MIN_PAIRS and rms < 6.0 and inlier_ratio > 0.5
            and quality > 0.3 and self._stable_count >= 2
        )
        return {
            "type": "calibration",
            "q": [float(v) for v in X.as_quat()],  # camera -> head, [x, y, z, w]
            "euler_deg": [round(float(pitch), 2), round(float(yaw), 2), round(float(roll), 2)],
            "tan_h": float(tan_h),
            "tan_v": float(tan_v),
            "hfov_deg": float(np.degrees(2 * np.arctan(tan_h))),
            "vfov_deg": float(np.degrees(2 * np.arctan(tan_v))),
            "cx": float(0.5 + x[4]),
            "cy": float(0.5 + x[5]),
            "dt_ms": float(x[6] * 1000),
            "k1": float(x[7]),
            "k2": float(x[8]),
            "rms_px": rms,
            "inlier_ratio": inlier_ratio,
            "pairs": len(pairs),
            "quality": quality,
            "converged": bool(converged),
            "width": W,
            "height": H,
        }
