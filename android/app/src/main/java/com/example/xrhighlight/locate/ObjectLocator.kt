package com.example.xrhighlight.locate

import androidx.xr.runtime.math.Vector3
import com.example.xrhighlight.Detection
import com.example.xrhighlight.camera.FrameMeta
import com.example.xrhighlight.geometry.CameraView

/**
 * A sighting of one detected object in space: the viewing ray through the center of its box,
 * its angular size, and (if it could be measured) the distance along the ray.
 */
data class Observation(
    val detection: Detection,
    val origin: Vector3,
    val dir: Vector3,
    /** Distance along [dir] in meters, or null if unknown. */
    val distance: Float?,
    /** Relative 1-sigma error of [distance] (e.g. 0.08 = ±8%). */
    val distanceError: Float,
    /** Angular width / height of the box (radians, small-angle). */
    val angW: Float,
    val angH: Float,
    val timeNs: Long,
) {
    /** Angular radius of the box. */
    val angRadius get() = 0.5f * maxOf(angW, angH)
}

/** All sightings of one frame, plus the camera they were seen from. */
class LocatedFrame(val camera: CameraView, val observations: List<Observation>)

/**
 * Turns 2D detections into rays/points in space. This is the part that the XR system may take
 * over in the future (Spatial Annotations, see [SpatialAnnotationProbe]); the tracker and the
 * renderer only depend on this interface.
 */
interface ObjectLocator {
    fun locate(meta: FrameMeta, detections: List<Detection>): LocatedFrame

    /** "Now" on the clock used by [Observation.timeNs] (for extrapolation while rendering). */
    fun nowNs(): Long
}
