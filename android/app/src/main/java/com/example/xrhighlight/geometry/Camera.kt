package com.example.xrhighlight.geometry

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pinhole + radial distortion model of the image that is sent for detection.
 * Image coordinates (u, v) are normalized to [0, 1], v pointing down.
 */
data class CameraModel(
    /** tan(horizontal FOV / 2) and tan(vertical FOV / 2). */
    val tanHalfH: Float,
    val tanHalfV: Float,
    /** Principal point (normalized). */
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    /** Radial distortion (OpenCV k1, k2) in normalized coordinates. */
    val k1: Float = 0f,
    val k2: Float = 0f,
) {
    /** Unit ray in camera axes (x right, y up, -z forward) for image point (u, v). */
    fun ray(u: Float, v: Float): Vector3 {
        val xd = (u - cx) * 2f * tanHalfH
        val yd = (v - cy) * 2f * tanHalfV
        var x = xd
        var y = yd
        repeat(5) { // undistort (fixed-point iteration)
            val r2 = x * x + y * y
            val s = 1f + k1 * r2 + k2 * r2 * r2
            x = xd / s
            y = yd / s
        }
        return Vector3(x, -y, -1f).toNormalized()
    }

    companion object {
        /** Used when neither metadata nor calibration is available. */
        val FALLBACK = CameraModel(tanHalfH = 0.70f, tanHalfV = 0.525f)
    }
}

/** Camera pose (perception space) + model for one frame. */
class CameraView(val pose: Pose, val model: CameraModel, val timeNs: Long) {
    val origin: Vector3 get() = pose.translation

    /** World-space unit ray through image point (u, v). */
    fun ray(u: Float, v: Float): Vector3 = (pose.rotation * model.ray(u, v)).toNormalized()

    /** True if [p] projects inside the central [margin] fraction of the image. */
    fun isInView(p: Vector3, margin: Float): Boolean {
        val local = pose.inverse.rotation * (p - pose.translation)
        if (local.z >= -0.1f) return false
        val u = local.x / -local.z / (2f * model.tanHalfH) + model.cx
        val v = -local.y / -local.z / (2f * model.tanHalfV) + model.cy
        val m = (1f - margin) / 2f
        return u in m..(1f - m) && v in m..(1f - m)
    }
}

/**
 * Result of the server-side auto calibration (server/calibration.py). The JSON format is the
 * server message, which is also what we persist on the device.
 */
data class AutoCalibration(
    /** Camera -> head rotation. */
    val rotation: Quaternion,
    val camera: CameraModel,
    /** Look up the head pose at imageTime + timeOffset. */
    val timeOffsetMs: Float,
    val rmsPx: Float,
    val pairs: Int,
    val quality: Float,
    val converged: Boolean,
    /** pitch, yaw, roll in degrees (for display). */
    val eulerDeg: List<Float>,
    val hfovDeg: Float,
    val vfovDeg: Float,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", "calibration")
        .put("q", JSONArray(listOf(rotation.x, rotation.y, rotation.z, rotation.w).map { it.toDouble() }))
        .put("tan_h", camera.tanHalfH.toDouble()).put("tan_v", camera.tanHalfV.toDouble())
        .put("cx", camera.cx.toDouble()).put("cy", camera.cy.toDouble())
        .put("k1", camera.k1.toDouble()).put("k2", camera.k2.toDouble())
        .put("dt_ms", timeOffsetMs.toDouble()).put("rms_px", rmsPx.toDouble())
        .put("pairs", pairs).put("quality", quality.toDouble()).put("converged", converged)
        .put("euler_deg", JSONArray(eulerDeg.map { it.toDouble() }))
        .put("hfov_deg", hfovDeg.toDouble()).put("vfov_deg", vfovDeg.toDouble())

    companion object {
        fun fromJson(j: JSONObject): AutoCalibration {
            // Older app versions persisted "euler"/"hfov"/"vfov" and no "converged" flag.
            val q = j.getJSONArray("q")
            val e = j.optJSONArray("euler_deg") ?: j.getJSONArray("euler")
            fun f(key: String, default: Double? = null): Float {
                val legacy = key.removeSuffix("_deg")
                return when {
                    j.has(key) -> j.getDouble(key)
                    j.has(legacy) -> j.getDouble(legacy)
                    default != null -> default
                    else -> throw IllegalArgumentException("missing $key")
                }.toFloat()
            }
            return AutoCalibration(
                rotation = Quaternion(
                    q.getDouble(0).toFloat(), q.getDouble(1).toFloat(),
                    q.getDouble(2).toFloat(), q.getDouble(3).toFloat(),
                ),
                camera = CameraModel(f("tan_h"), f("tan_v"), f("cx"), f("cy"), f("k1", 0.0), f("k2", 0.0)),
                timeOffsetMs = f("dt_ms"),
                rmsPx = f("rms_px"),
                pairs = j.getInt("pairs"),
                quality = f("quality"),
                converged = j.optBoolean("converged", true),
                eulerDeg = (0 until e.length()).map { e.getDouble(it).toFloat() },
                hfovDeg = f("hfov_deg"),
                vfovDeg = f("vfov_deg"),
            )
        }
    }
}
