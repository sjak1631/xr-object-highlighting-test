package com.example.xrhighlight.camera

import androidx.xr.runtime.math.Pose
import com.example.xrhighlight.geometry.CameraModel

/** What we knew at the moment a frame was captured; needed to place its detections in space. */
data class FrameMeta(
    val frameId: Long,
    /** Camera exposure timestamp (SystemClock.elapsedRealtimeNanos clock). */
    val imageTimeNs: Long,
    /** Head pose (perception space) at exposure time, without time-offset correction. */
    val headPose: Pose,
    /** Camera model from Camera2 metadata (used until auto calibration has converged). */
    val camera: CameraModel,
    val sentAtMs: Long,
)
