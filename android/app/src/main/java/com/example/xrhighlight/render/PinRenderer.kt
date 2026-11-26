package com.example.xrhighlight.render

import android.content.Context
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.scene
import com.example.xrhighlight.tracking.TrackedObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Draws one pin per tracked object as a SceneCore panel, every display frame.
 *
 * - Position/size are smoothed with frame-rate independent exponential smoothing (fast while an
 *   object moves, slow for corrections of locked objects).
 * - Pins fade in when an object appears and fade out when it disappears.
 * - Pins keep a constant angular size, so labels of distant objects stay readable.
 *
 * Must be used from the main thread.
 */
class PinRenderer(private val context: Context, private val session: Session) {
    private class Panel(val entity: PanelEntity, val view: PinView, var size: FloatSize2d)

    private class Pin(val panel: Panel) {
        var pos: Vector3? = null
        var height = 0f
        var alpha = 0f
    }

    private val pins = HashMap<Int, Pin>()
    private val fading = mutableListOf<Pin>()
    private val pool = ArrayDeque<Panel>()
    private var lastFrameNs = 0L

    /**
     * @param objects objects to show
     * @param head current head pose (perception space), or null if tracking is lost
     * @param nowNs current time on the clock of [TrackedObject.positionAt]
     */
    fun render(objects: List<TrackedObject>, head: Pose?, nowNs: Long) {
        val frameNs = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f else ((frameNs - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
        lastFrameNs = frameNs

        // Objects that are gone fade out.
        val ids = objects.mapTo(HashSet()) { it.id }
        pins.keys.filter { it !in ids }.forEach { id -> fading += pins.remove(id)!! }
        val it = fading.iterator()
        while (it.hasNext()) {
            val pin = it.next()
            pin.alpha -= dt / FADE_OUT_S
            if (pin.alpha <= 0f) {
                release(pin.panel)
                it.remove()
            } else {
                pin.panel.entity.setAlpha(pin.alpha)
            }
        }

        if (head == null) return
        for (obj in objects) {
            val pin = pins.getOrPut(obj.id) { Pin(acquire()) }

            // Smooth position and height.
            val target = obj.positionAt(nowNs)
            val k = 1f - exp(-dt / if (obj.isMoving) TAU_MOVING_S else TAU_LOCKED_S)
            val p = pin.pos?.let { it + (target - it) * k } ?: target
            pin.pos = p
            pin.height = if (pin.height == 0f) obj.height else pin.height + (obj.height - pin.height) * k
            pin.alpha = min(1f, pin.alpha + dt / FADE_IN_S)

            // Layout: ring (panel bottom edge) on the object, label above the object's top.
            val facing = Pose.fromLookAt(p, p + (p - head.translation), Vector3.Up) // face the user
            val dist = max(0.3f, (p - head.translation).length)
            val w = WIDTH_RAD * dist
            val ringPad = w * 0.08f
            val size = FloatSize2d(w, w * PinView.CHIP_RATIO + pin.height * 0.5f + LINE_RAD * dist + ringPad)
            val pose = Pose(p + facing.up * (size.height / 2f - ringPad / 2f), facing.rotation)

            val panel = pin.panel
            panel.entity.setPose(session.scene.perceptionSpace.transformPoseTo(pose, session.scene.activitySpace))
            if (relDiff(size.width, panel.size.width) > 0.03f || relDiff(size.height, panel.size.height) > 0.03f) {
                panel.entity.size = size
                panel.size = size
            }
            panel.entity.setAlpha(pin.alpha)
            panel.view.bind(obj.label)
        }
    }

    /** Remove all pins immediately. */
    fun clear() {
        (pins.values + fading).forEach { release(it.panel) }
        pins.clear()
        fading.clear()
    }

    fun dispose() {
        clear()
        pool.forEach { it.entity.dispose() }
        pool.clear()
    }

    private fun acquire(): Panel {
        pool.removeFirstOrNull()?.let {
            it.entity.setAlpha(0f)
            it.entity.setEnabled(true)
            return it
        }
        val view = PinView(context)
        val size = FloatSize2d(0.2f, 0.2f)
        val entity = PanelEntity.create(
            session = session,
            view = view,
            dimensions = size,
            name = "pin",
            pose = Pose.Identity,
            parent = session.scene.activitySpace,
        )
        entity.setAlpha(0f)
        return Panel(entity, view, size)
    }

    private fun release(panel: Panel) {
        panel.entity.setEnabled(false)
        pool.addLast(panel)
    }

    private fun relDiff(a: Float, b: Float) = abs(a - b) / max(1e-4f, min(abs(a), abs(b)))

    companion object {
        private const val WIDTH_RAD = 0.16f // panel width ~9 deg (label chip ~1.8 deg high)
        private const val LINE_RAD = 0.05f // line above the object's top ~3 deg
        private const val TAU_MOVING_S = 0.08f
        private const val TAU_LOCKED_S = 0.25f
        private const val FADE_IN_S = 0.2f
        private const val FADE_OUT_S = 0.3f
    }
}
