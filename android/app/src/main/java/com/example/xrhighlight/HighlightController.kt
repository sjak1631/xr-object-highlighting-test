package com.example.xrhighlight

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.arcore.hitTest
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Ray
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.scene
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min

/**
 * Turns 2D detections into world-locked highlight panels.
 *
 * Pipeline per detection:
 *  1. Back-project the box center into a ray using the head pose recorded at capture time
 *     (this compensates the network + inference latency: the result lands where the object was,
 *     not where the user is looking now).
 *  2. Hit-test the ray against the environment (planes / depth). If nothing is hit, fall back to
 *     the previous distance of the matching track or [Calibration.defaultDistance].
 *  3. Associate with an existing track of the same label by angular distance and smooth it.
 *
 * Every frame [tick] re-orients the panels to face the user. All math is done in perception
 * space and converted to activity space only when setting entity poses.
 *
 * Must be used from the main thread.
 */
class HighlightController(
    private val context: Context,
    private val session: Session,
    private val calibration: () -> Calibration,
) {
    private class Track(
        val id: Int,
        val label: String,
        var center: Vector3,
        var width: Float,
        var height: Float,
        var score: Float,
        var lastSeenMs: Long,
        var hits: Int = 1,
        var misses: Int = 0,
        var panel: Panel? = null,
    )

    private class Panel(val entity: PanelEntity, val view: HighlightView, var size: FloatSize2d)

    private val arDevice = ArDevice.getInstance(session)
    private val tracks = mutableListOf<Track>()
    private val pool = ArrayDeque<Panel>()
    private var nextId = 0
    private var hitTestAvailable = true

    /** Minimum number of detections before a track is shown (filters one-frame false positives). */
    var minHits = 2
    val trackCount get() = tracks.count { it.panel != null }

    fun currentHeadPose(): Pose? {
        val state = arDevice.state.value
        return if (state.trackingState == TrackingState.TRACKING) state.devicePose else null
    }

    fun onResult(result: DetectionResult, meta: FrameMeta) {
        val now = SystemClock.elapsedRealtime()
        val cal = calibration()
        val camPose = meta.headPose compose cameraInHead(cal)
        val tanH = meta.camera.tanHalfH * cal.fovScale
        val tanV = meta.camera.tanHalfV * cal.fovScale

        val matched = HashSet<Track>()
        for (det in result.detections.sortedByDescending { it.score }) {
            val dirCam = Vector3((2f * det.cx - 1f) * tanH, -(2f * det.cy - 1f) * tanV, -1f).toNormalized()
            val dir = (camPose.rotation * dirCam).toNormalized()
            val origin = camPose.translation
            // Angular radius of the box, used for association.
            val angRadius = 0.5f * max(det.w * 2f * tanH, det.h * 2f * tanV)

            val track = tracks
                .filter { it.label == det.label && it !in matched }
                .map { it to angle(dir, it.center - origin) }
                .filter { (_, a) -> a < max(0.08f, angRadius) }
                .minByOrNull { (_, a) -> a }
                ?.first

            val dist = raycastDistance(Ray(origin, dir))
                ?: track?.let { (it.center - origin).length }
                ?: cal.defaultDistance
            val center = origin + dir * dist
            val w = det.w * 2f * tanH * dist
            val h = det.h * 2f * tanV * dist

            if (track != null) {
                track.center = Vector3.lerp(track.center, center, SMOOTHING)
                track.width = lerp(track.width, w, SMOOTHING)
                track.height = lerp(track.height, h, SMOOTHING)
                track.score = lerp(track.score, det.score, SMOOTHING)
                track.lastSeenMs = now
                track.hits++
                track.misses = 0
                matched += track
            } else {
                val t = Track(nextId++, det.label, center, w, h, det.score, now)
                tracks += t
                matched += t
            }
        }

        // Tracks that should have been visible in this frame but were not detected count a miss.
        // Tracks outside the camera view are kept for a while so that highlights stay in place
        // when the user looks away and back.
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t in matched) continue
            val inView = isInView(t.center, camPose, tanH, tanV)
            if (inView) t.misses++
            val expired = (inView && t.misses >= MAX_MISSES) || now - t.lastSeenMs > OUT_OF_VIEW_TTL_MS
            if (expired) {
                t.panel?.let(::release)
                it.remove()
            }
        }
    }

    /** Call once per display frame. */
    fun tick() {
        val head = currentHeadPose() ?: return
        val toActivity = { p: Pose ->
            session.scene.perceptionSpace.transformPoseTo(p, session.scene.activitySpace)
        }
        for (t in tracks) {
            if (t.hits < minHits) continue
            val panel = t.panel ?: acquire().also { t.panel = it }
            // Face the user: panel's -Z (forward) points away from the head.
            val away = t.center + (t.center - head.translation)
            val pose = Pose.fromLookAt(t.center, away, Vector3.Up)
            panel.entity.setPose(toActivity(pose))
            val target = FloatSize2d(
                max(MIN_SIZE, t.width * PADDING),
                max(MIN_SIZE, t.height * PADDING),
            )
            if (relDiff(target.width, panel.size.width) > 0.05f ||
                relDiff(target.height, panel.size.height) > 0.05f
            ) {
                panel.entity.size = target
                panel.size = target
            }
            panel.view.bind(t.label, t.score)
        }
        debugLog(head)
    }

    private var lastDebugLogMs = 0L
    private var testPanel: Panel? = null

    private fun debugLog(head: Pose) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDebugLogMs < 1000) return
        lastDebugLogMs = now
        val headAct = session.scene.perceptionSpace.transformPoseTo(head, session.scene.activitySpace)
        Log.i(TAG, "head perception=${head.translation} fwd=${head.forward} activity=${headAct.translation}")
        for (t in tracks) {
            val p = t.panel ?: continue
            Log.i(
                TAG,
                "track#${t.id} ${t.label} center=${t.center} dist=${(t.center - head.translation).length}" +
                    " size=${p.size} entityPose(activity)=${p.entity.getPose()} " +
                    "view=${p.view.width}x${p.view.height} attached=${p.view.isAttachedToWindow}",
            )
        }
    }

    /** Debug: place a fixed panel 1 m in front of the current head pose. */
    fun showTestPanel() {
        val head = currentHeadPose() ?: run { Log.w(TAG, "test panel: no head pose"); return }
        val center = head.translation + head.forward * 1f
        val pose = Pose.fromLookAt(center, center + (center - head.translation), Vector3.Up)
        val panel = testPanel ?: acquire().also { testPanel = it }
        panel.entity.setPose(session.scene.perceptionSpace.transformPoseTo(pose, session.scene.activitySpace))
        panel.entity.size = FloatSize2d(0.3f, 0.3f)
        panel.size = FloatSize2d(0.3f, 0.3f)
        panel.view.bind("TEST", 1f)
        Log.i(TAG, "test panel at perception=$center activity=${panel.entity.getPose()}")
    }

    fun clear() {
        tracks.forEach { t -> t.panel?.let(::release) }
        tracks.clear()
    }

    fun dispose() {
        clear()
        pool.forEach { it.entity.dispose() }
        pool.clear()
    }

    private fun raycastDistance(ray: Ray): Float? {
        if (!hitTestAvailable) return null
        return try {
            hitTest(session, ray)
                .firstOrNull { it.distance in MIN_HIT_DISTANCE..MAX_HIT_DISTANCE }
                ?.distance
        } catch (e: IllegalStateException) {
            // Plane tracking disabled / not supported on this device.
            Log.w(TAG, "hit test unavailable: ${e.message}")
            hitTestAvailable = false
            null
        }
    }

    private fun isInView(p: Vector3, cam: Pose, tanH: Float, tanV: Float): Boolean {
        val local = cam.inverse.rotation * (p - cam.translation)
        if (local.z >= -0.05f) return false
        val x = local.x / -local.z
        val y = local.y / -local.z
        return abs(x) < tanH * 0.9f && abs(y) < tanV * 0.9f
    }

    private fun cameraInHead(c: Calibration) = Pose(
        Vector3(c.offsetX, c.offsetY, c.offsetZ),
        Quaternion.fromEulerAngles(c.pitchDeg, c.yawDeg, c.rollDeg),
    )

    private fun acquire(): Panel {
        pool.removeFirstOrNull()?.let {
            it.entity.setEnabled(true)
            return it
        }
        val view = HighlightView(context)
        val size = FloatSize2d(0.2f, 0.2f)
        val entity = PanelEntity.create(
            session = session,
            view = view,
            dimensions = size,
            name = "highlight",
            pose = Pose.Identity,
            parent = session.scene.activitySpace,
        )
        return Panel(entity, view, size)
    }

    private fun release(p: Panel) {
        p.entity.setEnabled(false)
        pool.addLast(p)
    }

    private fun angle(a: Vector3, b: Vector3): Float {
        val bl = b.length
        if (bl < 1e-5f) return 0f
        val d = (a.dot(b) / (a.length * bl)).coerceIn(-1f, 1f)
        return acos(d)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun relDiff(a: Float, b: Float) = abs(a - b) / max(1e-4f, min(abs(a), abs(b)))

    companion object {
        private const val TAG = "HighlightController"
        private const val SMOOTHING = 0.5f
        private const val MAX_MISSES = 3
        private const val OUT_OF_VIEW_TTL_MS = 10_000L
        private const val MIN_HIT_DISTANCE = 0.15f
        private const val MAX_HIT_DISTANCE = 10f
        private const val MIN_SIZE = 0.04f
        private const val PADDING = 1.12f
    }
}
