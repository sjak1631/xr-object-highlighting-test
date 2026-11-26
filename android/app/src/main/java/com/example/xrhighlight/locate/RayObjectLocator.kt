package com.example.xrhighlight.locate

import android.os.SystemClock
import android.util.Log
import androidx.xr.arcore.HitResult
import androidx.xr.arcore.Plane
import androidx.xr.arcore.PlaneType
import androidx.xr.arcore.hitTest
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Ray
import androidx.xr.runtime.math.Vector3
import com.example.xrhighlight.Detection
import com.example.xrhighlight.camera.FrameMeta
import com.example.xrhighlight.geometry.AutoCalibration
import com.example.xrhighlight.geometry.CameraView
import com.example.xrhighlight.xr.PoseHistory

/**
 * Our own locator:
 *  1. Camera pose at exposure time = head pose (interpolated from [PoseHistory] at the image
 *     timestamp + calibrated time offset) composed with the calibrated camera-to-head rotation.
 *  2. Ray through the box center using the calibrated intrinsics/distortion.
 *  3. Distance from ARCore hit tests: preferably where the ray through the bottom of the box meets
 *     the supporting horizontal surface (footprint), otherwise where the center ray hits anything.
 *
 * Distances are only priors; the tracker refines positions by triangulating rays over time.
 */
class RayObjectLocator(
    private val session: Session,
    private val poseHistory: PoseHistory,
    /** Converged auto calibration, or null to use the Camera2 metadata model. */
    private val calibration: () -> AutoCalibration?,
) : ObjectLocator {
    private var hitTestAvailable = true

    override fun nowNs(): Long = SystemClock.elapsedRealtimeNanos() + offsetNs()

    private fun offsetNs(): Long = ((calibration()?.timeOffsetMs ?: 0f) * 1e6f).toLong()

    override fun locate(meta: FrameMeta, detections: List<Detection>): LocatedFrame {
        val cal = calibration()
        val timeNs = meta.imageTimeNs + offsetNs()
        val head = if (cal != null) poseHistory.poseAt(timeNs) ?: meta.headPose else meta.headPose
        val cameraPose = if (cal != null) head compose Pose(Vector3.Zero, cal.rotation) else head
        val camera = CameraView(cameraPose, cal?.camera ?: meta.camera, timeNs)

        val observations = detections.map { det ->
            val dir = camera.ray(det.cx, det.cy)
            val (distance, error) = estimateDistance(camera, det, dir)
            Observation(
                detection = det,
                origin = camera.origin,
                dir = dir,
                distance = distance,
                distanceError = error,
                angW = det.w * 2f * camera.model.tanHalfH,
                angH = det.h * 2f * camera.model.tanHalfV,
                timeNs = timeNs,
            )
        }
        return LocatedFrame(camera, observations)
    }

    /** Returns (distance along [dir] or null, relative error). */
    private fun estimateDistance(camera: CameraView, det: Detection, dir: Vector3): Pair<Float?, Float> {
        val origin = camera.origin
        val fwd = camera.pose.forward
        // 1. Footprint on a horizontal surface (most objects stand on something).
        if (det.y2 < 0.97f) {
            val floor = raycast(Ray(origin, camera.ray(det.cx, det.y2))) {
                (it.trackable as? Plane)?.type == PlaneType.HORIZONTAL_UPWARD_FACING
            }
            if (floor != null) {
                // Same depth along the camera axis as the footprint.
                val d = (floor - origin).dot(fwd) / dir.dot(fwd)
                if (d in MIN_DISTANCE..MAX_DISTANCE) return d to ERROR_FOOTPRINT
            }
        }
        // 2. Whatever the center ray hits (may be the wall behind the object).
        val hit = raycast(Ray(origin, dir)) { true } ?: return null to ERROR_UNKNOWN
        return (hit - origin).length to ERROR_CENTER_RAY
    }

    private fun raycast(ray: Ray, accept: (HitResult) -> Boolean): Vector3? {
        if (!hitTestAvailable) return null
        return try {
            hitTest(session, ray)
                .firstOrNull { it.distance in MIN_DISTANCE..MAX_DISTANCE && accept(it) }
                ?.hitPose?.translation
        } catch (e: IllegalStateException) {
            Log.w(TAG, "hit test unavailable: ${e.message}")
            hitTestAvailable = false
            null
        }
    }

    companion object {
        private const val TAG = "RayObjectLocator"
        private const val MIN_DISTANCE = 0.15f
        private const val MAX_DISTANCE = 8f
        private const val ERROR_FOOTPRINT = 0.08f
        private const val ERROR_CENTER_RAY = 0.25f
        private const val ERROR_UNKNOWN = 1.5f
    }
}
