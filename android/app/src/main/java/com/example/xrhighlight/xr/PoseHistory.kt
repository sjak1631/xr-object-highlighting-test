package com.example.xrhighlight.xr

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Ring buffer of head poses stamped with SystemClock.elapsedRealtimeNanos(), the same clock
 * as the camera image timestamps (Aura reports SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME).
 *
 * Lets us look up the head pose at the exposure time of a frame instead of the (later)
 * time at which the frame reached the app, which removes most of the "smear" when the
 * head moves.
 */
class PoseHistory(private val capacity: Int = 512) {
    private val times = LongArray(capacity)
    private val poses = arrayOfNulls<Pose>(capacity)
    private var head = 0
    private var size = 0

    @Synchronized
    fun add(timeNs: Long, pose: Pose) {
        if (size > 0 && timeNs <= times[(head - 1 + capacity) % capacity]) return
        times[head] = timeNs
        poses[head] = pose
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** Interpolated pose at [timeNs]; clamps to the ends of the buffer. Null if empty. */
    @Synchronized
    fun poseAt(timeNs: Long): Pose? {
        if (size == 0) return null
        val oldest = (head - size + capacity) % capacity
        val newest = (head - 1 + capacity) % capacity
        if (timeNs <= times[oldest]) return poses[oldest]
        if (timeNs >= times[newest]) return poses[newest]
        // Binary search over the logical order.
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (times[(oldest + mid) % capacity] <= timeNs) lo = mid else hi = mid
        }
        val a = (oldest + lo) % capacity
        val b = (oldest + hi) % capacity
        val ratio = (timeNs - times[a]).toFloat() / (times[b] - times[a]).coerceAtLeast(1)
        return Pose.lerp(poses[a]!!, poses[b]!!, ratio)
    }

    /** World-frame angular velocity (rad/s) around [timeNs], from poses +-[halfWindowNs]. */
    fun angularVelocity(timeNs: Long, halfWindowNs: Long = 20_000_000L): Vector3 {
        val a = poseAt(timeNs - halfWindowNs) ?: return Vector3.Zero
        val b = poseAt(timeNs + halfWindowNs) ?: return Vector3.Zero
        val dq = b.rotation * a.rotation.inverse
        val rv = rotationVector(dq)
        return rv * (1e9f / (2 * halfWindowNs))
    }

    companion object {
        fun rotationVector(q: Quaternion): Vector3 {
            // Shortest path
            val s = if (q.w < 0) -1f else 1f
            val x = q.x * s
            val y = q.y * s
            val z = q.z * s
            val w = q.w * s
            val n = sqrt(x * x + y * y + z * z)
            if (n < 1e-7f) return Vector3(2 * x, 2 * y, 2 * z)
            val angle = 2f * atan2(n, w)
            return Vector3(x / n * angle, y / n * angle, z / n * angle)
        }
    }
}
