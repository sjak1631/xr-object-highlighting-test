package com.example.xrhighlight

import android.graphics.Color
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import java.util.zip.CRC32

/** One detection from the server. Box is normalized to [0, 1] in image coordinates (y down). */
data class Detection(
    val label: String,
    val score: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
) {
    val cx get() = (x1 + x2) * 0.5f
    val cy get() = (y1 + y2) * 0.5f
    val w get() = x2 - x1
    val h get() = y2 - y1
}

data class DetectionResult(
    val frameId: Long,
    val inferMs: Float,
    val detections: List<Detection>,
)

/**
 * Pinhole model of the analysed image: tangent of the half field of view on each axis,
 * already corrected for the crop/rotation of the image that is actually sent.
 */
data class CameraModel(
    val tanHalfH: Float,
    val tanHalfV: Float,
    /** Principal point, normalized to [0, 1] image coordinates. */
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    /** Radial distortion (OpenCV k1, k2) in normalized coordinates. */
    val k1: Float = 0f,
    val k2: Float = 0f,
) {
    /** Unit ray (OpenXR camera axes: x right, y up, -z forward) for normalized image point (u, v). */
    fun rayCamera(u: Float, v: Float): androidx.xr.runtime.math.Vector3 {
        val xd = (u - cx) * 2f * tanHalfH
        val yd = (v - cy) * 2f * tanHalfV
        var x = xd
        var y = yd
        repeat(5) {
            val r2 = x * x + y * y
            val s = 1f + k1 * r2 + k2 * r2 * r2
            x = xd / s
            y = yd / s
        }
        return androidx.xr.runtime.math.Vector3(x, -y, -1f).toNormalized()
    }

    companion object {
        /** Used when the camera does not report intrinsics. */
        val FALLBACK = CameraModel(tanHalfH = 0.70f, tanHalfV = 0.525f) // ~70 x 55 deg
    }
}

/** What we knew at the moment a frame was captured; needed to place results in the world. */
data class FrameMeta(
    val frameId: Long,
    /** Camera exposure timestamp (elapsedRealtimeNanos clock). */
    val imageTimeNs: Long,
    /** Head pose in perception space at exposure time (no time-offset correction). */
    val headPose: Pose,
    /** Camera model derived from Camera2 metadata (used until auto calibration converges). */
    val camera: CameraModel,
    val sentAtMs: Long,
)

/** Result of the server-side auto calibration (server/calibration.py). */
data class AutoCalibration(
    /** Camera -> head rotation. */
    val rotation: Quaternion,
    val camera: CameraModel,
    /** Pose lookup should use imageTime + timeOffset. */
    val timeOffsetMs: Float,
    val rmsPx: Float,
    val pairs: Int,
    val quality: Float,
    val converged: Boolean,
    val eulerDeg: List<Float>,
    val hfovDeg: Float,
    val vfovDeg: Float,
)

/**
 * Camera-to-head extrinsics and other knobs that have to be tuned per device, because the
 * exact position of the RGB camera relative to the tracked head pose is not exposed by the API.
 */
data class Calibration(
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val offsetZ: Float = 0f,
    val fovScale: Float = 1f,
    /** Distance used when the hit test finds no surface along the ray (meters). */
    val defaultDistance: Float = 1.5f,
)

/** Same label -> hue mapping as server/webcam_demo.py so colors match across tools. */
fun labelColor(label: String): Int {
    val crc = CRC32().apply { update(label.toByteArray(Charsets.UTF_8)) }.value
    val hue = (crc % 360).toFloat()
    return Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
}

fun parseLabels(text: String): List<String> =
    text.split(',', '、', '，', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
