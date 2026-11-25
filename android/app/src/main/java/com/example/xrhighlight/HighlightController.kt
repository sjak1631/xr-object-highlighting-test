package com.example.xrhighlight

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.HitResult
import androidx.xr.arcore.Plane
import androidx.xr.arcore.PlaneType
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-object tracker that turns 2D detections into world-space labels.
 *
 * Track states
 *  - TENTATIVE: collecting observations (not shown).
 *  - LOCKED: position triangulated from several rays and pinned with an ARCore [Anchor]. Detections
 *    near it only confirm it (and add rays from new viewpoints for refinement), so it never jitters.
 *  - MOVING: a locked object was detected clearly displaced [DISPLACED_TO_MOVE] times in a row; it
 *    follows detections with a constant-velocity filter and is LOCKED again once it stops.
 *
 * Keeping the scene clean
 *  - Existence score: +1 when detected, -1 when the camera looks at where the label is and the
 *    detector does not see it. At 0 the label fades out. Labels outside the camera view are kept.
 *  - Global association: all (detection, track) pairs of the same label are matched cheapest-first,
 *    with a tight gate first and a wide gate (moved objects) only for leftovers.
 *  - Duplicate suppression: a detection that overlaps an already matched track of the same label
 *    never spawns a new track, and two shown labels of the same class that overlap on screen are
 *    merged into the more reliable one.
 *
 * Display
 *  - Frame-rate independent exponential smoothing of position/size (fast while moving, slow for
 *    locked refinements) plus fade-in / fade-out, driven by [tick] on every display frame.
 *
 * Must be used from the main thread.
 */
class HighlightController(
    private val context: Context,
    private val session: Session,
    private val poseHistory: PoseHistory,
    private val calibration: () -> Calibration,
    private val autoCalibration: () -> AutoCalibration?,
    private val markerStyle: () -> MarkerStyle = { MarkerStyle.PIN },
) {
    private enum class Phase { TENTATIVE, LOCKED, MOVING }

    /**
     * One detection of a track. [origin]/[dir] is the ray through the box center (used for
     * triangulation); [center] is the point on that ray at the hit-test distance, which is only a
     * prior with relative uncertainty [depthRel].
     */
    private class Obs(
        val origin: Vector3,
        val dir: Vector3,
        val center: Vector3,
        val angW: Float,
        val angH: Float,
        val depthRel: Float,
        val timeNs: Long,
    ) {
        val width get() = angW * (center - origin).length
        val height get() = angH * (center - origin).length
    }

    private inner class Track(val id: Int, val label: String) {
        var phase = Phase.TENTATIVE
        val obs = ArrayList<Obs>()
        var center = Vector3.Zero
        var velocity = Vector3.Zero
        var lastObsTimeNs = 0L
        var width = 0f
        var height = 0f
        var score = 0f
        var lastSeenMs = 0L
        var existence = 0f
        var displaced = 0
        var obsSinceRefine = 0
        var anchor: Anchor? = null

        // Display state
        var panel: Panel? = null
        var displayPos: Vector3? = null
        var displayW = 0f
        var displayH = 0f
        var alpha = 0f

        val shown get() = phase != Phase.TENTATIVE

        /** Best position estimate at [timeNs] (anchors may be refined by the tracking system). */
        fun positionAt(timeNs: Long): Vector3 = when (phase) {
            Phase.LOCKED -> anchor?.state?.value?.pose?.translation ?: center
            Phase.MOVING -> {
                val dt = ((timeNs - lastObsTimeNs) / 1e9f).coerceIn(0f, MAX_EXTRAPOLATION_S)
                center + velocity * dt
            }
            Phase.TENTATIVE -> center
        }

        /** Angular radius of the object seen from [from]. */
        fun angRadius(from: Vector3, timeNs: Long): Float =
            0.5f * max(width, height) / max(0.2f, (positionAt(timeNs) - from).length)
    }

    /** A panel that is fading out after its track was removed or merged. */
    private class Dying(val panel: Panel, var alpha: Float)

    private class Panel(val entity: PanelEntity, val view: HighlightView, var size: FloatSize2d)

    /** Camera pose + model used for one frame. */
    private class CamFrame(val pose: Pose, val model: CameraModel, val timeNs: Long) {
        fun ray(u: Float, v: Float): Vector3 = (pose.rotation * model.rayCamera(u, v)).toNormalized()

        fun isInView(p: Vector3, margin: Float): Boolean {
            val local = pose.inverse.rotation * (p - pose.translation)
            if (local.z >= -0.1f) return false
            val u = local.x / -local.z / (2f * model.tanHalfH) + model.cx
            val v = -local.y / -local.z / (2f * model.tanHalfV) + model.cy
            val m = (1f - margin) / 2f
            return u in m..(1f - m) && v in m..(1f - m)
        }
    }

    private val arDevice = ArDevice.getInstance(session)
    private val tracks = mutableListOf<Track>()
    private val dying = mutableListOf<Dying>()
    private val pool = ArrayDeque<Panel>()
    private var nextId = 0
    private var hitTestAvailable = true
    private var lastTickNs = 0L

    val trackCount get() = tracks.count { it.shown }

    fun currentHeadPose(): Pose? {
        val state = arDevice.state.value
        return if (state.trackingState == TrackingState.TRACKING) state.devicePose else null
    }

    private fun camFrameFor(meta: FrameMeta): CamFrame {
        val cal = calibration()
        val auto = autoCalibration()
        val timeNs = meta.imageTimeNs + ((auto?.timeOffsetMs ?: 0f) * 1e6f).toLong()
        val head = auto?.let { poseHistory.poseAt(timeNs) } ?: meta.headPose
        val base = auto?.camera ?: meta.camera
        val model = base.copy(
            tanHalfH = base.tanHalfH * cal.fovScale,
            tanHalfV = base.tanHalfV * cal.fovScale,
        )
        val manualRot = Quaternion.fromEulerAngles(cal.pitchDeg, cal.yawDeg, cal.rollDeg)
        val rot = (auto?.rotation ?: Quaternion.Identity) * manualRot
        val camInHead = Pose(Vector3(cal.offsetX, cal.offsetY, cal.offsetZ), rot)
        return CamFrame(head compose camInHead, model, timeNs)
    }

    // ================================================================ detection update

    private class Cand(val det: Detection, val dir: Vector3, val angRadius: Float) {
        val tightGate get() = max(TIGHT_GATE_RAD, angRadius * 1.2f)
        val wideGate get() = max(WIDE_GATE_RAD, angRadius * 3f)
    }

    fun onResult(result: DetectionResult, meta: FrameMeta, confidence: Float) {
        val now = SystemClock.elapsedRealtime()
        val cam = camFrameFor(meta)
        val origin = cam.pose.translation

        val cands = result.detections.map { det ->
            Cand(
                det,
                cam.ray(det.cx, det.cy),
                0.5f * max(det.w * 2f * cam.model.tanHalfH, det.h * 2f * cam.model.tanHalfV),
            )
        }

        // ---- 1. Global association, cheapest first. Pass 1 tight, pass 2 wide (shown tracks only).
        val assigned = arrayOfNulls<Track>(cands.size)
        val matched = HashSet<Track>()
        for (wide in listOf(false, true)) {
            val pairs = ArrayList<Triple<Int, Track, Float>>()
            cands.forEachIndexed { i, c ->
                if (assigned[i] != null) return@forEachIndexed
                for (t in tracks) {
                    if (t.label != c.det.label || t in matched) continue
                    if (wide && !t.shown) continue
                    val a = angle(c.dir, t.positionAt(cam.timeNs) - origin)
                    val gate = if (wide || t.phase == Phase.MOVING) c.wideGate else c.tightGate
                    if (a < gate) pairs += Triple(i, t, a / gate)
                }
            }
            pairs.sortBy { it.third }
            for ((i, t, _) in pairs) {
                if (assigned[i] != null || t in matched) continue
                assigned[i] = t
                matched += t
            }
        }

        // ---- 2. Update matched tracks / create new ones.
        cands.forEachIndexed { i, c ->
            val det = c.det
            var track = assigned[i]

            if (track == null) {
                if (det.score < confidence + NEW_TRACK_SCORE_MARGIN) return@forEachIndexed
                // Second box on an object that already has a label (or a label that is just a bit
                // off): never spawn a copy next to it.
                val overlapsExisting = tracks.any {
                    it.label == det.label &&
                        angle(c.dir, it.positionAt(cam.timeNs) - origin) <
                        max(c.tightGate, it.angRadius(origin, cam.timeNs) + c.angRadius)
                }
                if (overlapsExisting) return@forEachIndexed
            }

            val measured = estimateDistance(cam, det, c.dir)
            val dist = measured
                ?: track?.let { (it.positionAt(cam.timeNs) - origin).length }
                ?: calibration().defaultDistance
            val obs = Obs(
                origin = origin,
                dir = c.dir,
                center = origin + c.dir * dist,
                angW = det.w * 2f * cam.model.tanHalfH,
                angH = det.h * 2f * cam.model.tanHalfV,
                depthRel = when {
                    measured == null -> DEPTH_REL_NONE
                    depthSource == "footprint" -> DEPTH_REL_FOOTPRINT
                    else -> DEPTH_REL_CENTER_RAY
                },
                timeNs = cam.timeNs,
            )

            if (track?.phase == Phase.LOCKED) {
                val pinned = track.positionAt(cam.timeNs)
                val a = angle(c.dir, pinned - origin)
                // Displaced = off in the image AND off in 3D (avoids false "moves" from depth noise).
                val off3d = (obs.center - pinned).length > max(0.08f, 0.4f * max(track.width, track.height))
                confirm(track, det.score, now)
                if (a < c.tightGate || !off3d) {
                    track.displaced = 0
                    // Only rays that really point at the pinned object may refine its position.
                    if (a < c.tightGate) refineLocked(track, obs)
                    return@forEachIndexed
                }
                if (++track.displaced < DISPLACED_TO_MOVE) return@forEachIndexed
                startMoving(track)
            }

            if (track == null) {
                track = Track(nextId++, det.label)
                tracks += track
                matched += track
            }
            when (track.phase) {
                Phase.MOVING -> updateMoving(track, obs)
                else -> updateTentative(track, obs)
            }
            confirm(track, det.score, now)
        }

        // ---- 3. Existence bookkeeping for tracks not detected in this frame.
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t in matched) continue
            val inView = cam.isInView(t.positionAt(cam.timeNs), IN_VIEW_MARGIN)
            if (inView) t.existence -= if (t.phase == Phase.MOVING) 1.5f else 1f
            if (t.phase == Phase.MOVING && cam.timeNs - t.lastObsTimeNs > STOP_EXTRAPOLATING_NS) {
                t.center = t.positionAt(cam.timeNs)
                t.velocity = Vector3.Zero
                t.lastObsTimeNs = cam.timeNs
            }
            val expired = when (t.phase) {
                Phase.TENTATIVE -> now - t.lastSeenMs > TENTATIVE_TTL_MS
                else -> t.existence <= 0f || now - t.lastSeenMs > OUT_OF_VIEW_TTL_MS
            }
            if (expired) {
                Log.i(TAG, "remove #${t.id} ${t.label} (${t.phase}, existence=${t.existence})")
                remove(t)
                it.remove()
            }
        }

        // ---- 4. Merge shown labels of the same class that overlap from this viewpoint.
        mergeDuplicates(origin, cam.timeNs, matched)
    }

    private fun confirm(t: Track, score: Float, now: Long) {
        t.lastSeenMs = now
        t.score = max(t.score * 0.9f, score)
        if (t.shown) t.existence = min(EXISTENCE_MAX, t.existence + 1f)
    }

    private fun mergeDuplicates(origin: Vector3, timeNs: Long, matched: Set<Track>) {
        val shown = tracks.filter { it.shown }
        val removed = HashSet<Track>()
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            val a = shown[i]
            val b = shown[j]
            if (a in removed || b in removed || a.label != b.label) continue
            val sep = angle(a.positionAt(timeNs) - origin, b.positionAt(timeNs) - origin)
            val limit = max(MERGE_MIN_RAD, 0.8f * max(a.angRadius(origin, timeNs), b.angRadius(origin, timeNs)))
            if (sep > limit) continue
            // Keep the one detected now, then the more established one.
            fun rank(t: Track) = (if (t in matched) 1000f else 0f) + t.existence * 10f + t.obs.size
            val (keep, drop) = if (rank(a) >= rank(b)) a to b else b to a
            Log.i(TAG, "merge duplicate #${drop.id} into #${keep.id} ${keep.label}")
            keep.existence = min(EXISTENCE_MAX, max(keep.existence, drop.existence))
            keep.lastSeenMs = max(keep.lastSeenMs, drop.lastSeenMs)
            removed += drop
        }
        removed.forEach { remove(it) }
        tracks.removeAll(removed)
    }

    private fun updateTentative(t: Track, o: Obs) {
        t.obs += o
        if (t.obs.size > MAX_TENTATIVE_OBS) t.obs.removeAt(0)
        t.center = triangulate(t.obs, null)
        updateSize(t)
        t.lastObsTimeNs = o.timeNs
        tryLock(t)
    }

    private fun startMoving(t: Track) {
        Log.i(TAG, "#${t.id} ${t.label} started moving")
        runCatching { t.anchor?.detach() }
        t.anchor = null
        t.phase = Phase.MOVING
        t.velocity = Vector3.Zero
        t.obs.clear()
        t.displaced = 0
    }

    /** Constant-velocity alpha-beta filter on the 3D position. */
    private fun updateMoving(t: Track, o: Obs) {
        val dt = ((o.timeNs - t.lastObsTimeNs) / 1e9f).coerceAtLeast(0.03f)
        if (t.obs.isEmpty()) {
            t.center = o.center
        } else {
            val predicted = t.center + t.velocity * dt.coerceAtMost(MAX_EXTRAPOLATION_S)
            val residual = o.center - predicted
            t.center = predicted + residual * MOVING_ALPHA
            var v = t.velocity + residual * (MOVING_BETA / dt)
            if (v.length > MAX_SPEED) v = v.toNormalized() * MAX_SPEED
            t.velocity = v
        }
        t.width += (o.width - t.width) * 0.5f
        t.height += (o.height - t.height) * 0.5f
        t.lastObsTimeNs = o.timeNs
        t.obs += o
        if (t.obs.size > STILL_OBS) t.obs.removeAt(0)

        // Stopped? -> lock again at the new place.
        if (t.obs.size >= STILL_OBS) {
            val med = medianOf(t.obs.map { it.center })
            val spread = t.obs.maxOf { (it.center - med).length }
            if (spread < max(0.05f, 0.25f * max(t.width, t.height)) && t.velocity.length < STILL_SPEED) {
                t.center = triangulate(t.obs, med)
                t.velocity = Vector3.Zero
                updateSize(t)
                lock(t)
            }
        }
    }

    /**
     * Keep collecting rays for a locked object from new viewpoints. When enough new ones arrived,
     * re-triangulate; if the estimate moved noticeably, re-anchor there (the label glides to the new
     * place in [tick]). Small viewpoint changes give large parallax for far objects, so this fixes
     * the depth error that hit-testing alone has at a distance.
     */
    private fun refineLocked(t: Track, o: Obs) {
        val novel = t.obs.none {
            (it.origin - o.origin).length < MIN_NEW_VIEWPOINT_M && angle(it.dir, o.dir) < MIN_NEW_VIEW_ANGLE_RAD
        }
        if (!novel) return
        t.obs += o
        if (t.obs.size > MAX_LOCKED_OBS) t.obs.removeAt(0)
        if (++t.obsSinceRefine < REFINE_EVERY) return
        t.obsSinceRefine = 0
        val current = t.positionAt(o.timeNs)
        val p = triangulate(t.obs, current)
        if ((p - current).length < REFINE_MIN_SHIFT_M) return
        Log.i(TAG, "refine #${t.id} ${t.label}: $current -> $p (${t.obs.size} rays)")
        t.center = p
        updateSize(t)
        runCatching { t.anchor?.detach() }
        t.anchor = (Anchor.create(session, Pose(p, Quaternion.Identity)) as? AnchorCreateSuccess)?.anchor
    }

    private fun updateSize(t: Track) {
        t.width = median(t.obs.map { it.angW * (t.center - it.origin).length })
        t.height = median(t.obs.map { it.angH * (t.center - it.origin).length })
    }

    private fun tryLock(t: Track) {
        if (t.obs.size < LOCK_HITS) return
        // Rays must agree with each other (median angular residual to the triangulated point).
        val resid = median(t.obs.map { angle(it.dir, t.center - it.origin) })
        if (resid > MAX_RAY_RESIDUAL_RAD) return

        // Same object already labelled (seen from the latest viewpoint)? Then just drop this one.
        val o = t.obs.last()
        val dup = tracks.firstOrNull {
            it !== t && it.shown && it.label == t.label &&
                angle(it.positionAt(o.timeNs) - o.origin, t.center - o.origin) <
                max(MERGE_MIN_RAD, it.angRadius(o.origin, o.timeNs) + t.angRadius(o.origin, o.timeNs))
        }
        if (dup != null) {
            dup.existence = min(EXISTENCE_MAX, dup.existence + 1f)
            t.obs.clear() // expires as tentative
            return
        }
        lock(t)
    }

    private fun lock(t: Track) {
        t.phase = Phase.LOCKED
        t.displaced = 0
        t.obsSinceRefine = 0
        t.existence = max(t.existence, EXISTENCE_INITIAL)
        t.anchor = when (val r = Anchor.create(session, Pose(t.center, Quaternion.Identity))) {
            is AnchorCreateSuccess -> r.anchor
            else -> {
                Log.w(TAG, "anchor create failed: $r (using fixed position)")
                null
            }
        }
        Log.i(TAG, "locked #${t.id} ${t.label} at ${t.center} size=${t.width}x${t.height} depth=$depthSource")
    }

    // ================================================================ geometry

    /**
     * Weighted least-squares point closest to all observation rays, regularized by the per-ray
     * depth priors (hit tests). Rays from one viewpoint constrain only the direction; once the head
     * has moved, parallax pins down the distance and the (weaker) priors stop mattering.
     */
    private fun triangulate(obs: List<Obs>, init: Vector3?): Vector3 {
        var p = init ?: medianOf(obs.map { it.center })
        repeat(2) {
            val a = DoubleArray(9)
            val b = DoubleArray(3)
            for (o in obs) {
                val r = max(0.2f, (p - o.origin).length).toDouble()
                val sr = SIGMA_RAY_RAD * r
                val wRay = 1.0 / (sr * sr)
                val sd = o.depthRel * r
                val wPrior = 1.0 / (sd * sd)
                val d = doubleArrayOf(o.dir.x.toDouble(), o.dir.y.toDouble(), o.dir.z.toDouble())
                val org = doubleArrayOf(o.origin.x.toDouble(), o.origin.y.toDouble(), o.origin.z.toDouble())
                val q = doubleArrayOf(o.center.x.toDouble(), o.center.y.toDouble(), o.center.z.toDouble())
                for (i in 0..2) {
                    for (j in 0..2) {
                        val m = (if (i == j) 1.0 else 0.0) - d[i] * d[j]
                        a[i * 3 + j] += wRay * m
                        b[i] += wRay * m * org[j]
                    }
                    a[i * 3 + i] += wPrior
                    b[i] += wPrior * q[i]
                }
            }
            p = solve3(a, b) ?: return p
        }
        return p
    }

    /** Cramer's rule for a 3x3 system (row-major [a]). */
    private fun solve3(a: DoubleArray, b: DoubleArray): Vector3? {
        fun det(m: DoubleArray) = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        val d = det(a)
        if (abs(d) < 1e-12) return null
        fun col(k: Int) = a.copyOf().also { m -> for (i in 0..2) m[i * 3 + k] = b[i] }
        return Vector3((det(col(0)) / d).toFloat(), (det(col(1)) / d).toFloat(), (det(col(2)) / d).toFloat())
    }

    /**
     * Distance along [dir] (the box-center ray) to the object.
     *
     * 1. Objects usually stand on something: the ray through the bottom-center of the box hits the
     *    supporting horizontal surface at the object's footprint. That depth (along the camera
     *    axis) is a much better estimate than the center ray, which often passes the object and
     *    hits the wall/table behind it.
     * 2. Otherwise use the center ray hit, 3. otherwise null.
     */
    private fun estimateDistance(cam: CamFrame, det: Detection, dir: Vector3): Float? {
        val origin = cam.pose.translation
        val fwd = cam.pose.forward
        if (det.y2 < 0.97f) {
            val bottomDir = cam.ray(det.cx, det.y2)
            val floorHit = raycast(Ray(origin, bottomDir)) {
                (it.trackable as? Plane)?.type == PlaneType.HORIZONTAL_UPWARD_FACING
            }
            if (floorHit != null) {
                val depth = (floorHit - origin).dot(fwd)
                val d = depth / dir.dot(fwd)
                if (d in MIN_HIT_DISTANCE..MAX_HIT_DISTANCE) {
                    depthSource = "footprint"
                    return d
                }
            }
        }
        val hit = raycast(Ray(origin, dir)) { true } ?: run { depthSource = "none"; return null }
        depthSource = "center-ray"
        return (hit - origin).length
    }

    /** Debug: which method produced the last distance estimate. */
    var depthSource = "none"
        private set

    private fun raycast(ray: Ray, accept: (HitResult) -> Boolean): Vector3? {
        if (!hitTestAvailable) return null
        return try {
            hitTest(session, ray)
                .firstOrNull { it.distance in MIN_HIT_DISTANCE..MAX_HIT_DISTANCE && accept(it) }
                ?.hitPose?.translation
        } catch (e: IllegalStateException) {
            Log.w(TAG, "hit test unavailable: ${e.message}")
            hitTestAvailable = false
            null
        }
    }

    // ================================================================ display

    /** Call on every display frame (Choreographer). */
    fun tick() {
        val nowRt = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastTickNs == 0L) 0.016f else ((nowRt - lastTickNs) / 1e9f).coerceIn(0.001f, 0.1f)
        lastTickNs = nowRt

        // Fade out removed labels.
        val di = dying.iterator()
        while (di.hasNext()) {
            val d = di.next()
            d.alpha -= dt / FADE_OUT_S
            if (d.alpha <= 0f) {
                d.panel.entity.setEnabled(false)
                pool.addLast(d.panel)
                di.remove()
            } else {
                d.panel.entity.setAlpha(d.alpha)
            }
        }

        val head = currentHeadPose() ?: return
        // Track positions are on the camera clock (+ calibrated offset).
        val nowNs = nowRt + ((autoCalibration()?.timeOffsetMs ?: 0f) * 1e6f).toLong()
        val style = markerStyle()
        for (t in tracks) {
            if (!t.shown) continue
            val panel = t.panel ?: acquire().also {
                t.panel = it
                t.alpha = 0f
                t.displayPos = null
            }

            // Exponential smoothing (frame-rate independent).
            val target = t.positionAt(nowNs)
            val tau = if (t.phase == Phase.MOVING) TAU_MOVING_S else TAU_LOCKED_S
            val k = 1f - exp(-dt / tau)
            val c = t.displayPos?.let { it + (target - it) * k } ?: target
            t.displayPos = c
            val ks = 1f - exp(-dt / TAU_SIZE_S)
            t.displayW = if (t.displayW == 0f) t.width else t.displayW + (t.width - t.displayW) * ks
            t.displayH = if (t.displayH == 0f) t.height else t.displayH + (t.height - t.displayH) * ks
            t.alpha = min(1f, t.alpha + dt / FADE_IN_S)
            panel.entity.setAlpha(t.alpha)

            // Face the user: panel's -Z (forward) points away from the head.
            val facing = Pose.fromLookAt(c, c + (c - head.translation), Vector3.Up)
            val size: FloatSize2d
            val pose: Pose
            if (style == MarkerStyle.PIN) {
                // Constant angular size, so far labels stay readable. The ring at the panel's
                // bottom edge sits on the object; the label floats above the object's top.
                val dist = max(0.3f, (c - head.translation).length)
                val w = PIN_WIDTH_RAD * dist
                val chipH = w * PIN_CHIP_RATIO
                val line = t.displayH * 0.5f + PIN_LINE_RAD * dist
                val ringPad = w * 0.08f
                size = FloatSize2d(w, chipH + line + ringPad)
                pose = Pose(c + facing.up * (size.height / 2f - ringPad / 2f), facing.rotation)
            } else {
                size = FloatSize2d(max(MIN_SIZE, t.displayW * PADDING), max(MIN_SIZE, t.displayH * PADDING))
                pose = facing
            }
            panel.entity.setPose(toActivity(pose))
            if (relDiff(size.width, panel.size.width) > 0.03f ||
                relDiff(size.height, panel.size.height) > 0.03f
            ) {
                panel.entity.size = size
                panel.size = size
            }
            panel.view.bind(t.label, t.score, style)
        }
    }

    fun clear() {
        tracks.forEach(::remove)
        tracks.clear()
        testPanel?.let(::releaseNow)
        testPanel = null
    }

    fun dispose() {
        clear()
        dying.forEach { releaseNow(it.panel) }
        dying.clear()
        pool.forEach { it.entity.dispose() }
        pool.clear()
    }

    /** Detach the anchor and fade the label out. */
    private fun remove(t: Track) {
        t.panel?.let { dying += Dying(it, t.alpha) }
        t.panel = null
        t.displayPos = null
        runCatching { t.anchor?.detach() }
        t.anchor = null
    }

    private fun toActivity(p: Pose): Pose =
        session.scene.perceptionSpace.transformPoseTo(p, session.scene.activitySpace)

    // ---------------------------------------------------------------- debug

    private var testPanel: Panel? = null

    /** Debug: place a fixed panel 1 m in front of the current head pose. */
    fun showTestPanel() {
        val head = currentHeadPose() ?: run { Log.w(TAG, "test panel: no head pose"); return }
        val center = head.translation + head.forward * 1f
        val pose = Pose.fromLookAt(center, center + (center - head.translation), Vector3.Up)
        val panel = testPanel ?: acquire().also { testPanel = it }
        panel.entity.setAlpha(1f)
        panel.entity.setPose(toActivity(pose))
        panel.entity.size = FloatSize2d(0.3f, 0.3f)
        panel.size = FloatSize2d(0.3f, 0.3f)
        panel.view.bind("TEST", 1f, MarkerStyle.BOX)
    }

    // ---------------------------------------------------------------- panels

    private fun acquire(): Panel {
        pool.removeFirstOrNull()?.let {
            it.entity.setAlpha(0f)
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
        entity.setAlpha(0f)
        return Panel(entity, view, size)
    }

    private fun releaseNow(p: Panel) {
        p.entity.setEnabled(false)
        pool.addLast(p)
    }

    // ---------------------------------------------------------------- math

    private fun angle(a: Vector3, b: Vector3): Float {
        val al = a.length
        val bl = b.length
        if (al < 1e-5f || bl < 1e-5f) return 0f
        return acos((a.dot(b) / (al * bl)).coerceIn(-1f, 1f))
    }

    private fun median(v: List<Float>): Float = v.sorted()[v.size / 2]

    private fun medianOf(v: List<Vector3>) =
        Vector3(median(v.map { it.x }), median(v.map { it.y }), median(v.map { it.z }))

    private fun relDiff(a: Float, b: Float) = abs(a - b) / max(1e-4f, min(abs(a), abs(b)))

    companion object {
        private const val TAG = "HighlightController"

        // Association gates (radians)
        private const val TIGHT_GATE_RAD = 0.10f // ~6 deg
        private const val WIDE_GATE_RAD = 0.40f // ~23 deg
        private const val MERGE_MIN_RAD = 0.07f // labels closer than ~4 deg on screen are one

        // Existence (detected +1, in view but missed -1)
        private const val EXISTENCE_INITIAL = 3f
        private const val EXISTENCE_MAX = 6f
        private const val IN_VIEW_MARGIN = 0.8f // only count misses well inside the camera view

        // Static objects
        private const val LOCK_HITS = 3
        private const val MAX_TENTATIVE_OBS = 7
        private const val TENTATIVE_TTL_MS = 1500L
        private const val OUT_OF_VIEW_TTL_MS = 60_000L
        private const val NEW_TRACK_SCORE_MARGIN = 0.05f

        // Moving objects
        private const val DISPLACED_TO_MOVE = 2
        private const val MOVING_ALPHA = 0.6f
        private const val MOVING_BETA = 0.3f
        private const val MAX_SPEED = 3f // m/s
        private const val MAX_EXTRAPOLATION_S = 0.35f
        private const val STOP_EXTRAPOLATING_NS = 800_000_000L
        private const val STILL_OBS = 5
        private const val STILL_SPEED = 0.06f // m/s

        // Triangulation
        private const val SIGMA_RAY_RAD = 0.012 // detection-center + calibration noise (~0.7 deg)
        private const val DEPTH_REL_FOOTPRINT = 0.08f
        private const val DEPTH_REL_CENTER_RAY = 0.25f
        private const val DEPTH_REL_NONE = 1.5f
        private const val MAX_RAY_RESIDUAL_RAD = 0.05f
        private const val MAX_LOCKED_OBS = 40
        private const val MIN_NEW_VIEWPOINT_M = 0.03f
        private const val MIN_NEW_VIEW_ANGLE_RAD = 0.02f
        private const val REFINE_EVERY = 3
        private const val REFINE_MIN_SHIFT_M = 0.03f

        // Geometry
        private const val MIN_HIT_DISTANCE = 0.15f
        private const val MAX_HIT_DISTANCE = 8f
        private const val MIN_SIZE = 0.04f
        private const val PADDING = 1.12f

        // Display smoothing / fades (seconds)
        private const val TAU_MOVING_S = 0.08f
        private const val TAU_LOCKED_S = 0.25f
        private const val TAU_SIZE_S = 0.2f
        private const val FADE_IN_S = 0.2f
        private const val FADE_OUT_S = 0.3f

        // Pin marker (angular sizes, radians)
        private const val PIN_WIDTH_RAD = 0.16f // panel width ~9 deg; chip is 20% of it (~1.8 deg)
        private const val PIN_LINE_RAD = 0.05f // line above the object's top ~3 deg
    }
}
