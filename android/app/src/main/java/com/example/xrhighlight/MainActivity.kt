package com.example.xrhighlight

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.xrhighlight.camera.CameraFrameSource
import com.example.xrhighlight.camera.FrameMeta
import com.example.xrhighlight.geometry.AutoCalibration
import com.example.xrhighlight.locate.ObjectLocator
import com.example.xrhighlight.locate.RayObjectLocator
import com.example.xrhighlight.locate.SpatialAnnotationProbe
import com.example.xrhighlight.net.DetectionClient
import com.example.xrhighlight.render.PinRenderer
import com.example.xrhighlight.tracking.ObjectTracker
import com.example.xrhighlight.ui.ControlPanel
import com.example.xrhighlight.xr.PoseHistory
import com.example.xrhighlight.xr.XrEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Wires the pipeline together:
 *
 *   camera ──frames──▶ server (detection) ──boxes──▶ locator (box → ray/point in space)
 *          ──▶ tracker (stable objects) ──▶ renderer (pins, every display frame)
 */
class MainActivity : ComponentActivity() {
    private lateinit var state: AppState
    private lateinit var client: DetectionClient
    private lateinit var xr: XrEnvironment
    private var camera: CameraFrameSource? = null
    private var locator: ObjectLocator? = null
    private var tracker: ObjectTracker? = null
    private var renderer: PinRenderer? = null
    private var probe: SpatialAnnotationProbe? = null

    private val pendingFrames = ConcurrentHashMap<Long, FrameMeta>()
    private var lastResultAt = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val t = tracker
            val r = renderer
            val l = locator
            if (t != null && r != null && l != null) {
                val objects = t.visible
                r.render(objects, xr.headPose(), l.nowNs())
                if (state.visibleObjects != objects.size) state.visibleObjects = objects.size
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.CAMERA] == true) init() else state.cameraStatus = "camera permission denied"
        }

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?: return@registerForActivityResult
            // "コップとペットボトル" / "cup and bottle" -> comma separated
            state.labelsText = text
                .replace(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE), ",")
                .replace("と", ",")
                .replace("や", ",")
            applyLabels()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AppState(this)
        xr = XrEnvironment(this, lifecycleScope) { state.xrStatus = it }
        client = DetectionClient(
            scope = lifecycleScope,
            onResult = { r -> onMain { handleResult(r) } },
            onStatus = { connected, msg -> onMain { state.connected = connected; state.connectionMessage = msg } },
            onCalibration = { c -> onMain { handleCalibration(c) } },
        )

        setContent {
            ControlPanel(
                state = state,
                onToggleRun = ::toggleRun,
                onApplyLabels = ::applyLabels,
                onVoiceInput = ::startVoiceInput,
                onClearHighlights = ::clearObjects,
                onResetCalibration = {
                    state.calibration = null
                    state.save()
                    client.resetCalibration()
                    clearObjects()
                },
            )
        }

        val permissions = listOf(
            Manifest.permission.CAMERA,
            "android.permission.HEAD_TRACKING",
            "android.permission.SCENE_UNDERSTANDING_COARSE",
            "android.permission.SCENE_UNDERSTANDING_FINE",
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) init() else permissionLauncher.launch(missing.toTypedArray())

        // Render on every display frame while visible.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = Choreographer.getInstance().postFrameCallback(frameCallback)
            override fun onStop(owner: LifecycleOwner) = Choreographer.getInstance().removeFrameCallback(frameCallback)
        })
    }

    private fun init() {
        lifecycleScope.launch {
            val session = xr.start(enableSpatialAnnotations = ENABLE_SPATIAL_ANNOTATION_PROBE)
            if (session != null) {
                locator = RayObjectLocator(session, xr.poseHistory) { state.activeCalibration }
                tracker = ObjectTracker(session)
                renderer = PinRenderer(this@MainActivity, session)
                if (ENABLE_SPATIAL_ANNOTATION_PROBE) {
                    probe = SpatialAnnotationProbe(session, lifecycleScope) { onMain { state.cameraStatus = "probe: $it" } }
                }
            }
            // Without XR (e.g. a phone) detection still runs; results are only listed in the panel.
            camera = CameraFrameSource(
                context = this@MainActivity,
                lifecycleOwner = this@MainActivity,
                client = client,
                poseHistory = if (session != null) xr.poseHistory else null,
                onFrameSent = { meta -> pendingFrames[meta.frameId] = meta },
                rawFrameSink = { id, image -> probe?.onRawFrame(id, image) },
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) xr.requestFullSpace()
    }

    // ------------------------------------------------------------------ UI actions

    private fun toggleRun() {
        xr.requestFullSpace()
        if (state.running) {
            client.disconnect()
            camera?.stop()
            clearObjects()
            pendingFrames.clear()
            state.running = false
            state.connectionMessage = "Stopped"
            return
        }
        val cam = camera ?: run { state.cameraStatus = "camera not ready (permissions?)"; return }
        state.save()
        client.updateConfig(state.labels, state.confidence)
        client.connect(state.serverUrl.trim())
        cam.start { state.cameraStatus = it }
        state.running = true
    }

    private fun applyLabels() {
        state.save()
        clearObjects()
        client.updateConfig(state.labels, state.confidence)
    }

    private fun clearObjects() {
        tracker?.clear()
        renderer?.clear()
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "ハイライトしたい物を言ってください")
        }
        runCatching { voiceLauncher.launch(intent) }
            .onFailure { state.connectionMessage = "音声入力が利用できません: ${it.message}" }
    }

    // ------------------------------------------------------------------ server messages

    private fun handleResult(result: DetectionResult) {
        val meta = pendingFrames.remove(result.frameId) ?: return
        pendingFrames.keys.removeAll { it < result.frameId } // never answered

        val now = SystemClock.elapsedRealtime()
        if (lastResultAt > 0) state.fps = state.fps * 0.9f + 1000f / (now - lastResultAt).coerceAtLeast(1) * 0.1f
        lastResultAt = now
        state.latencyMs = state.latencyMs * 0.8f + (now - meta.sentAtMs) * 0.2f
        state.inferMs = state.inferMs * 0.8f + result.inferMs * 0.2f
        state.lastDetections = result.detections

        probe?.onResult(result)
        val frame = locator?.locate(meta, result.detections) ?: return
        tracker?.update(frame, minNewScore = state.confidence + NEW_OBJECT_SCORE_MARGIN)
    }

    private fun handleCalibration(c: AutoCalibration) {
        val prev = state.activeCalibration
        // Keep a converged (e.g. persisted) result until the new estimate converges as well.
        if (!c.converged && prev != null) return
        state.calibration = c
        if (!c.converged) return
        state.save()
        // Objects placed with noticeably different geometry would be misplaced: re-detect them.
        if (prev == null || significantChange(prev, c)) {
            Log.i(TAG, "calibration applied, clearing objects")
            clearObjects()
        }
    }

    private fun significantChange(a: AutoCalibration, b: AutoCalibration): Boolean {
        val angleDeg = Math.toDegrees(PoseHistory.rotationVector(a.rotation.inverse * b.rotation).length.toDouble())
        return angleDeg > 1.0 || kotlin.math.abs(a.camera.tanHalfH / b.camera.tanHalfH - 1f) > 0.02f
    }

    private fun onMain(block: () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { block() }
    }

    override fun onDestroy() {
        state.save()
        client.disconnect()
        camera?.shutdown()
        tracker?.clear()
        renderer?.dispose()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"

        /** New objects need a slightly higher score than the detection threshold (hysteresis). */
        private const val NEW_OBJECT_SCORE_MARGIN = 0.05f

        /**
         * Spatial Annotation experiment (system-side 3D tracking of image regions).
         * As of Aura build UXP4.261012.001.O5 (2026-11-25): enabling QUAD mode succeeds and starts
         * the system AnnotationTracking service, but the OpenXR extension
         * ANDROID_spatial_annotation_tracking is not exposed to apps, so startTracking() never
         * returns and nothing is tracked. Keep off; re-test after system updates.
         *
         * Developer-side requirements are met (Spatial API level 4, SCENE_UNDERSTANDING_FINE granted,
         * arcore 1.1.0-alpha02 + @OptIn). The runtime (libopenxr_android.so) does not enumerate the
         * extension because its internal rollout flag
         * com_android_xr/com.android.xr.flags.enable_oxr_android_spatial_extensions (and
         * .enable_oxr_spatial_entities for XR_EXT_spatial_image_tracking) is off in this build.
         * These flags are read from persist.device_config.com_android_xr.* properties, which a user
         * build does not let adb change, so this has to come with a system (OTA) update.
         */
        private const val ENABLE_SPATIAL_ANNOTATION_PROBE = false
    }
}
