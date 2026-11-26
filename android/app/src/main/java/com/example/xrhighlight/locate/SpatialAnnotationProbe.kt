package com.example.xrhighlight.locate

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.xr.arcore.SpatialAnnotation
import androidx.xr.arcore.SpatialAnnotationId
import androidx.xr.arcore.SpatialAnnotationImageFormat
import androidx.xr.arcore.SpatialAnnotationQuadAlignment
import androidx.xr.arcore.SpatialAnnotationTrackingOptions
import androidx.xr.runtime.ExperimentalSpatialAnnotationsApi
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Quad
import androidx.xr.runtime.math.Vector2
import com.example.xrhighlight.DetectionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Experiment: hand detections to the runtime's Spatial Annotation tracking (QUAD mode) and see
 * whether it tracks them in 3D for us. Runs once, on the first frame that has detections, and only
 * logs what happens (tag "SpatialAnnotationProbe").
 */
@OptIn(ExperimentalSpatialAnnotationsApi::class)
class SpatialAnnotationProbe(
    private val session: Session,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
) {
    private class Raw(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val timestampNanoTime: Long,
        val rotation: Int,
    )

    private val frames = object : LinkedHashMap<Long, Raw>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Raw>?) = size > 6
    }

    @Volatile private var started = false

    /** Called on the camera thread for every frame that is sent to the server. */
    fun onRawFrame(frameId: Long, image: ImageProxy) {
        if (started) return
        val plane = image.planes[0]
        val src = plane.buffer.duplicate().apply { rewind() }
        val copy = ByteBuffer.allocateDirect(src.remaining()).order(ByteOrder.nativeOrder())
        copy.put(src).rewind()
        // Camera timestamps are on the elapsedRealtimeNanos clock; the API wants System.nanoTime.
        val ts = image.imageInfo.timestamp + (System.nanoTime() - SystemClock.elapsedRealtimeNanos())
        synchronized(frames) {
            frames[frameId] = Raw(copy, image.width, image.height, plane.rowStride, ts, image.imageInfo.rotationDegrees)
        }
    }

    /** Called on the main thread with each detection result. */
    fun onResult(result: DetectionResult) {
        if (started || result.detections.isEmpty()) return
        val raw = synchronized(frames) { frames[result.frameId] } ?: return
        if (raw.rotation != 0) {
            report("skip: camera image is rotated by ${raw.rotation} deg")
            return
        }
        started = true
        val quads = result.detections.mapIndexed { i, d ->
            SpatialAnnotationId.fromString("${d.label}-$i") to Quad.fromMinMax(
                Vector2(d.x1 * raw.width, d.y1 * raw.height),
                Vector2(d.x2 * raw.width, d.y2 * raw.height),
            )
        }.toMap()
        report("startTracking with ${quads.size} quads on ${raw.width}x${raw.height} (stride ${raw.rowStride})")

        scope.launch {
            try {
                val options = SpatialAnnotationTrackingOptions.Builder(
                    raw.buffer, IntSize2d(raw.width, raw.height), raw.timestampNanoTime,
                )
                    .setRowStride(raw.rowStride)
                    .setFormat(SpatialAnnotationImageFormat.RGBA)
                    .setAlignment(SpatialAnnotationQuadAlignment.OBJECT)
                    .setQuads(quads)
                    .build()
                SpatialAnnotation.startTracking(session, options)
                report("startTracking returned OK, subscribing for 20 s")
            } catch (e: Throwable) {
                report("startTracking FAILED: ${e::class.simpleName}: ${e.message}")
                Log.w(TAG, "startTracking failed", e)
                return@launch
            }
            var lastLog = 0L
            var maxSeen = 0
            val finished = withTimeoutOrNull(20_000) {
                SpatialAnnotation.subscribe(session).collect { list ->
                    maxSeen = maxOf(maxSeen, list.size)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLog > 1000) {
                        lastLog = now
                        val desc = list.joinToString { a ->
                            val s = a.state.value
                            "${a.id}: ${s.trackingState} pos=${s.pose.translation}"
                        }
                        report("tracked ${list.size}: $desc")
                    }
                }
            }
            report("done: max tracked annotations = $maxSeen (${if (finished == null) "timeout" else "flow ended"})")
            runCatching { SpatialAnnotation.stopTrackingAllAnnotations(session) }
        }
    }

    private fun report(msg: String) {
        Log.i(TAG, msg)
        onStatus(msg)
    }

    companion object {
        private const val TAG = "SpatialAnnotationProbe"
    }
}
