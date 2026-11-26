package com.example.xrhighlight.tracking

import android.os.SystemClock
import android.util.Log
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import com.example.xrhighlight.geometry.CameraView
import com.example.xrhighlight.geometry.angleBetween
import com.example.xrhighlight.geometry.median
import com.example.xrhighlight.geometry.median3
import com.example.xrhighlight.geometry.solve3
import com.example.xrhighlight.locate.LocatedFrame
import com.example.xrhighlight.locate.Observation
import kotlin.math.max
import kotlin.math.min

/** What the renderer needs to know about a tracked object. */
interface TrackedObject {
    val id: Int
    val label: String
    val score: Float
    val isMoving: Boolean
    /** Physical height in meters (for placing the label above the object). */
    val height: Float
    /** Position (perception space) at [timeNs] on the locator's clock. */
    fun positionAt(timeNs: Long): Vector3
}

/**
 * Multi-object tracker: turns per-frame sightings ([LocatedFrame]) into stable objects in space.
 *
 * States
 *  - TENTATIVE: collecting sightings (not shown).
 *  - LOCKED: position triangulated from several rays and pinned with an ARCore [Anchor]. Later
 *    sightings only confirm it and add rays from new viewpoints (refinement), so it never jitters.
 *  - MOVING: a locked object was seen clearly displaced twice in a row; it follows sightings with a
 *    constant-velocity filter (extrapolated between detections) and is LOCKED again when it stops.
 *
 * Keeping the scene clean
 *  - Existence score: +1 when seen, -1 when the camera looks at the object's position but it is not
 *    detected; removed at 0. Objects outside the camera view are kept.
 *  - Global association (cheapest pairs first; tight gate, then a wide gate for leftovers).
 *  - A sighting overlapping an existing object of the same label never creates a new one, and
 *    shown objects of the same label that overlap on screen are merged.
 *
 * Must be used from the main thread.
 */
class ObjectTracker(private val session: Session) {
    private enum class Phase { TENTATIVE, LOCKED, MOVING }

    /** A sighting with its distance prior resolved to a point. */
    private class Ray(val obs: Observation, val prior: Vector3, val priorError: Float) {
        val origin get() = obs.origin
        val dir get() = obs.dir
        val timeNs get() = obs.timeNs
    }

    private inner class Track(override val id: Int, override val label: String) : TrackedObject {
        var phase = Phase.TENTATIVE
        val rays = ArrayList<Ray>()
        var center = Vector3.Zero
        var velocity = Vector3.Zero
        var lastRayTimeNs = 0L
        var width = 0f
        override var height = 0f
        override var score = 0f
        var lastSeenMs = 0L
        var existence = 0f
        var displaced = 0
        var raysSinceRefine = 0
        var anchor: Anchor? = null

        val shown get() = phase != Phase.TENTATIVE
        override val isMoving get() = phase == Phase.MOVING

        override fun positionAt(timeNs: Long): Vector3 = when (phase) {
            Phase.LOCKED -> anchor?.state?.value?.pose?.translation ?: center
            Phase.MOVING -> center + velocity *
                ((timeNs - lastRayTimeNs) / 1e9f).coerceIn(0f, MAX_EXTRAPOLATION_S)
            Phase.TENTATIVE -> center
        }

        /** Angular radius of the object seen from [from]. */
        fun angRadius(from: Vector3, timeNs: Long): Float =
            0.5f * max(width, height) / max(0.2f, (positionAt(timeNs) - from).length)
    }

    private val tracks = mutableListOf<Track>()
    private var nextId = 0

    /** Objects that should be displayed. */
    val visible: List<TrackedObject> get() = tracks.filter { it.shown }

    fun clear() {
        tracks.forEach { runCatching { it.anchor?.detach() } }
        tracks.clear()
    }

    /** Feed the sightings of one frame. New objects need a score of at least [minNewScore]. */
    fun update(frame: LocatedFrame, minNewScore: Float) {
        val now = SystemClock.elapsedRealtime()
        val camera = frame.camera
        val obs = frame.observations
        val matched = HashSet<Track>()
        val assigned = associate(camera, obs, matched)

        obs.forEachIndexed { i, o ->
            var track = assigned[i]
            val tight = tightGate(o)
            if (track == null) {
                if (o.detection.score < minNewScore) return@forEachIndexed
                // Second box on an object that already has a label: never spawn a copy.
                val duplicate = tracks.any {
                    it.label == o.detection.label &&
                        angleBetween(o.dir, it.positionAt(camera.timeNs) - o.origin) <
                        max(tight, it.angRadius(o.origin, camera.timeNs) + o.angRadius)
                }
                if (duplicate) return@forEachIndexed
            }
            val ray = toRay(o, track)

            if (track?.phase == Phase.LOCKED) {
                val pinned = track.positionAt(camera.timeNs)
                val off = angleBetween(o.dir, pinned - o.origin)
                // Displaced = off in the image AND in 3D (depth noise alone must not move it).
                val off3d = (ray.prior - pinned).length > max(0.08f, 0.4f * max(track.width, track.height))
                confirm(track, o.detection.score, now)
                if (off < tight || !off3d) {
                    track.displaced = 0
                    if (off < tight) refineLocked(track, ray)
                    return@forEachIndexed
                }
                if (++track.displaced < DISPLACED_TO_MOVE) return@forEachIndexed
                startMoving(track)
            }

            if (track == null) {
                track = Track(nextId++, o.detection.label).also { tracks += it }
                matched += track
            }
            if (track.phase == Phase.MOVING) updateMoving(track, ray) else updateTentative(track, ray)
            confirm(track, o.detection.score, now)
        }

        decay(camera, matched, now)
        mergeDuplicates(camera, matched)
    }

    // ------------------------------------------------------------------ association

    private fun tightGate(o: Observation) = max(TIGHT_GATE_RAD, o.angRadius * 1.2f)
    private fun wideGate(o: Observation) = max(WIDE_GATE_RAD, o.angRadius * 3f)

    /** Cheapest-first assignment; pass 1 with a tight gate, pass 2 with a wide gate (shown only). */
    private fun associate(camera: CameraView, obs: List<Observation>, matched: MutableSet<Track>): Array<Track?> {
        val assigned = arrayOfNulls<Track>(obs.size)
        for (wide in listOf(false, true)) {
            val pairs = ArrayList<Triple<Int, Track, Float>>()
            obs.forEachIndexed { i, o ->
                if (assigned[i] != null) return@forEachIndexed
                for (t in tracks) {
                    if (t.label != o.detection.label || t in matched || (wide && !t.shown)) continue
                    val a = angleBetween(o.dir, t.positionAt(camera.timeNs) - o.origin)
                    val gate = if (wide || t.phase == Phase.MOVING) wideGate(o) else tightGate(o)
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
        return assigned
    }

    /** Resolve the distance prior: measured, else the track's current distance, else a default. */
    private fun toRay(o: Observation, track: Track?): Ray {
        val dist = o.distance
            ?: track?.let { (it.positionAt(o.timeNs) - o.origin).length }
            ?: DEFAULT_DISTANCE
        return Ray(o, o.origin + o.dir * dist, o.distanceError)
    }

    private fun confirm(t: Track, score: Float, now: Long) {
        t.lastSeenMs = now
        t.score = max(t.score * 0.9f, score)
        if (t.shown) t.existence = min(EXISTENCE_MAX, t.existence + 1f)
    }

    // ------------------------------------------------------------------ life cycle

    private fun decay(camera: CameraView, matched: Set<Track>, now: Long) {
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t in matched) continue
            if (camera.isInView(t.positionAt(camera.timeNs), IN_VIEW_MARGIN)) {
                t.existence -= if (t.phase == Phase.MOVING) 1.5f else 1f
            }
            if (t.phase == Phase.MOVING && camera.timeNs - t.lastRayTimeNs > STOP_EXTRAPOLATING_NS) {
                t.center = t.positionAt(camera.timeNs)
                t.velocity = Vector3.Zero
                t.lastRayTimeNs = camera.timeNs
            }
            val expired = when (t.phase) {
                Phase.TENTATIVE -> now - t.lastSeenMs > TENTATIVE_TTL_MS
                else -> t.existence <= 0f || now - t.lastSeenMs > OUT_OF_VIEW_TTL_MS
            }
            if (expired) {
                Log.i(TAG, "remove #${t.id} ${t.label} (${t.phase})")
                runCatching { t.anchor?.detach() }
                it.remove()
            }
        }
    }

    private fun mergeDuplicates(camera: CameraView, matched: Set<Track>) {
        val shown = tracks.filter { it.shown }
        val drop = HashSet<Track>()
        val o = camera.origin
        val ts = camera.timeNs
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            val a = shown[i]
            val b = shown[j]
            if (a in drop || b in drop || a.label != b.label) continue
            val sep = angleBetween(a.positionAt(ts) - o, b.positionAt(ts) - o)
            if (sep > max(MERGE_MIN_RAD, 0.8f * max(a.angRadius(o, ts), b.angRadius(o, ts)))) continue
            // Keep the one seen now, then the more established one.
            fun rank(t: Track) = (if (t in matched) 1000f else 0f) + t.existence * 10f + t.rays.size
            val (keep, lose) = if (rank(a) >= rank(b)) a to b else b to a
            Log.i(TAG, "merge #${lose.id} into #${keep.id} ${keep.label}")
            keep.existence = min(EXISTENCE_MAX, max(keep.existence, lose.existence))
            keep.lastSeenMs = max(keep.lastSeenMs, lose.lastSeenMs)
            drop += lose
        }
        drop.forEach { runCatching { it.anchor?.detach() } }
        tracks.removeAll(drop)
    }

    // ------------------------------------------------------------------ static objects

    private fun updateTentative(t: Track, r: Ray) {
        t.rays += r
        if (t.rays.size > MAX_TENTATIVE_RAYS) t.rays.removeAt(0)
        t.center = triangulate(t.rays, null)
        updateSize(t)
        t.lastRayTimeNs = r.timeNs
        if (t.rays.size < LOCK_HITS) return
        // Rays must agree (median angular residual to the triangulated point).
        if (median(t.rays.map { angleBetween(it.dir, t.center - it.origin) }) > MAX_RAY_RESIDUAL_RAD) return
        // Already labelled from this viewpoint? Then this one is a duplicate.
        val dup = tracks.firstOrNull {
            it !== t && it.shown && it.label == t.label &&
                angleBetween(it.positionAt(r.timeNs) - r.origin, t.center - r.origin) <
                max(MERGE_MIN_RAD, it.angRadius(r.origin, r.timeNs) + t.angRadius(r.origin, r.timeNs))
        }
        if (dup != null) {
            dup.existence = min(EXISTENCE_MAX, dup.existence + 1f)
            t.rays.clear() // expires as tentative
            return
        }
        lock(t)
    }

    private fun lock(t: Track) {
        t.phase = Phase.LOCKED
        t.displaced = 0
        t.raysSinceRefine = 0
        t.existence = max(t.existence, EXISTENCE_INITIAL)
        t.anchor = createAnchor(t.center)
        Log.i(TAG, "lock #${t.id} ${t.label} at ${t.center}")
    }

    /**
     * Add rays of a locked object seen from new viewpoints and re-triangulate now and then; the
     * parallax from head movement corrects depth errors (important for distant objects).
     */
    private fun refineLocked(t: Track, r: Ray) {
        val novel = t.rays.none {
            (it.origin - r.origin).length < MIN_NEW_VIEWPOINT_M && angleBetween(it.dir, r.dir) < MIN_NEW_VIEW_RAD
        }
        if (!novel) return
        t.rays += r
        if (t.rays.size > MAX_LOCKED_RAYS) t.rays.removeAt(0)
        if (++t.raysSinceRefine < REFINE_EVERY) return
        t.raysSinceRefine = 0
        val current = t.positionAt(r.timeNs)
        val p = triangulate(t.rays, current)
        if ((p - current).length < REFINE_MIN_SHIFT_M) return
        t.center = p
        updateSize(t)
        runCatching { t.anchor?.detach() }
        t.anchor = createAnchor(p)
    }

    private fun createAnchor(p: Vector3): Anchor? =
        (Anchor.create(session, Pose(p, Quaternion.Identity)) as? AnchorCreateSuccess)?.anchor

    private fun updateSize(t: Track) {
        t.width = median(t.rays.map { it.obs.angW * (t.center - it.origin).length })
        t.height = median(t.rays.map { it.obs.angH * (t.center - it.origin).length })
    }

    // ------------------------------------------------------------------ moving objects

    private fun startMoving(t: Track) {
        Log.i(TAG, "#${t.id} ${t.label} started moving")
        runCatching { t.anchor?.detach() }
        t.anchor = null
        t.phase = Phase.MOVING
        t.velocity = Vector3.Zero
        t.rays.clear()
        t.displaced = 0
    }

    /** Constant-velocity alpha-beta filter; re-lock when the object has been still. */
    private fun updateMoving(t: Track, r: Ray) {
        val dt = ((r.timeNs - t.lastRayTimeNs) / 1e9f).coerceAtLeast(0.03f)
        if (t.rays.isEmpty()) {
            t.center = r.prior
        } else {
            val predicted = t.center + t.velocity * dt.coerceAtMost(MAX_EXTRAPOLATION_S)
            val residual = r.prior - predicted
            t.center = predicted + residual * MOVING_ALPHA
            var v = t.velocity + residual * (MOVING_BETA / dt)
            if (v.length > MAX_SPEED) v = v.toNormalized() * MAX_SPEED
            t.velocity = v
        }
        val range = (r.prior - r.origin).length
        t.width += (r.obs.angW * range - t.width) * 0.5f
        t.height += (r.obs.angH * range - t.height) * 0.5f
        t.lastRayTimeNs = r.timeNs
        t.rays += r
        if (t.rays.size > STILL_RAYS) t.rays.removeAt(0)

        if (t.rays.size >= STILL_RAYS && t.velocity.length < STILL_SPEED) {
            val med = median3(t.rays.map { it.prior })
            val spread = t.rays.maxOf { (it.prior - med).length }
            if (spread < max(0.05f, 0.25f * max(t.width, t.height))) {
                t.center = triangulate(t.rays, med)
                t.velocity = Vector3.Zero
                updateSize(t)
                lock(t)
            }
        }
    }

    // ------------------------------------------------------------------ triangulation

    /**
     * Weighted least-squares point closest to all rays, regularized by each ray's distance prior.
     * With one viewpoint the prior fixes the depth; once the head has moved, parallax does.
     */
    private fun triangulate(rays: List<Ray>, init: Vector3?): Vector3 {
        var p = init ?: median3(rays.map { it.prior })
        repeat(2) {
            val a = DoubleArray(9)
            val b = DoubleArray(3)
            for (r in rays) {
                val range = max(0.2f, (p - r.origin).length).toDouble()
                val wRay = 1.0 / sq(SIGMA_RAY_RAD * range)
                val wPrior = 1.0 / sq(r.priorError * range)
                val d = r.dir.toDoubles()
                val org = r.origin.toDoubles()
                val q = r.prior.toDoubles()
                for (i in 0..2) {
                    for (j in 0..2) {
                        val m = (if (i == j) 1.0 else 0.0) - d[i] * d[j] // projector orthogonal to the ray
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

    private fun sq(x: Double) = x * x
    private fun Vector3.toDoubles() = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())

    companion object {
        private const val TAG = "ObjectTracker"

        // Association (radians)
        private const val TIGHT_GATE_RAD = 0.10f // ~6 deg
        private const val WIDE_GATE_RAD = 0.40f // ~23 deg
        private const val MERGE_MIN_RAD = 0.07f // same-label objects closer than ~4 deg are one

        // Existence score
        private const val EXISTENCE_INITIAL = 3f
        private const val EXISTENCE_MAX = 6f
        private const val IN_VIEW_MARGIN = 0.8f
        private const val TENTATIVE_TTL_MS = 1500L
        private const val OUT_OF_VIEW_TTL_MS = 60_000L

        // Locking / refinement
        private const val LOCK_HITS = 3
        private const val MAX_TENTATIVE_RAYS = 7
        private const val MAX_LOCKED_RAYS = 40
        private const val MAX_RAY_RESIDUAL_RAD = 0.05f
        private const val MIN_NEW_VIEWPOINT_M = 0.03f
        private const val MIN_NEW_VIEW_RAD = 0.02f
        private const val REFINE_EVERY = 3
        private const val REFINE_MIN_SHIFT_M = 0.03f
        private const val SIGMA_RAY_RAD = 0.012 // detection-center + calibration noise (~0.7 deg)
        private const val DEFAULT_DISTANCE = 1.5f

        // Moving objects
        private const val DISPLACED_TO_MOVE = 2
        private const val MOVING_ALPHA = 0.6f
        private const val MOVING_BETA = 0.3f
        private const val MAX_SPEED = 3f // m/s
        private const val MAX_EXTRAPOLATION_S = 0.35f
        private const val STOP_EXTRAPOLATING_NS = 800_000_000L
        private const val STILL_RAYS = 5
        private const val STILL_SPEED = 0.06f // m/s
    }
}
