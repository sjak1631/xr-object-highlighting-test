package com.example.xrhighlight

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.xr.runtime.Config
import androidx.xr.runtime.DepthEstimationMode
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.PlaneTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.scene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState
    private lateinit var client: DetectionClient
    private var camera: CameraFrameSource? = null
    private var session: Session? = null
    private var highlights: HighlightController? = null
    private val pendingFrames = ConcurrentHashMap<Long, FrameMeta>()
    private var lastResultAt = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            Log.i(TAG, "permissions: $granted")
            if (granted[Manifest.permission.CAMERA] == true) initXr()
            else state.cameraStatus = "camera permission denied"
        }

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val text = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
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
        client = DetectionClient(
            scope = lifecycleScope,
            onResult = { result -> lifecycleScope.launch(Dispatchers.Main) { handleResult(result) } },
            onStatus = { connected, msg ->
                lifecycleScope.launch(Dispatchers.Main) {
                    state.connected = connected
                    state.connectionMessage = msg
                }
            },
        )

        setContent {
            ControlPanel(
                state = state,
                onToggleRun = ::toggleRun,
                onApplyLabels = ::applyLabels,
                onVoiceInput = ::startVoiceInput,
                onClearHighlights = { highlights?.clear() },
                onTestPanel = { highlights?.showTestPanel() },
            )
        }

        val wanted = listOf(
            Manifest.permission.CAMERA,
            "android.permission.HEAD_TRACKING",
            "android.permission.SCENE_UNDERSTANDING_COARSE",
            "android.permission.SCENE_UNDERSTANDING_FINE",
        )
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) initXr() else permissionLauncher.launch(missing.toTypedArray())

        // Re-orient / resize highlight panels every frame while visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    highlights?.let {
                        it.tick()
                        state.visibleHighlights = it.trackCount
                    }
                    delay(16)
                }
            }
        }
    }

    private fun initXr() {
        lifecycleScope.launch {
            when (val result = Session.create(this@MainActivity)) {
                is SessionCreateSuccess -> {
                    val s = result.session
                    session = s
                    trackingStatus = configureSession(s)
                    s.scene.addSpatialCapabilitiesChangedListener { caps ->
                        Log.i(TAG, "spatial capabilities changed: ${describe(caps)}")
                        updateXrStatus()
                    }
                    updateXrStatus()
                    // Only honoured while the activity has focus; also retried in onWindowFocusChanged.
                    requestFullSpace()
                    highlights = HighlightController(this@MainActivity, s) { state.calibration }
                }
                else -> {
                    // Not an XR device (e.g. a phone): detection still works, results are only
                    // listed in the panel. Useful for testing the server connection.
                    state.xrStatus = "XR: unavailable (${result::class.simpleName}) – 2D test mode"
                }
            }
            camera = CameraFrameSource(
                context = this@MainActivity,
                lifecycleOwner = this@MainActivity,
                client = client,
                headPoseProvider = { if (session == null) Pose.Identity else highlights?.currentHeadPose() },
                onFrameSent = { meta -> pendingFrames[meta.frameId] = meta },
            )
        }
    }

    private var trackingStatus = ""

    private fun describe(caps: Set<SpatialCapability>): String = buildList {
        if (SpatialCapability.SPATIAL_UI in caps) add("UI")
        if (SpatialCapability.SPATIAL_3D_CONTENT in caps) add("3D")
        if (SpatialCapability.PASSTHROUGH_CONTROL in caps) add("passthrough")
    }.ifEmpty { listOf("none") }.joinToString("+")

    private fun updateXrStatus() {
        val s = session ?: return
        val caps = s.scene.spatialCapabilities
        state.xrStatus = "XR: $trackingStatus | spatial=${describe(caps)}" +
            if (SpatialCapability.SPATIAL_UI !in caps) " (Full Space ではないため枠を表示できません)" else ""
    }

    private fun requestFullSpace() {
        val s = session ?: return
        if (SpatialCapability.SPATIAL_UI in s.scene.spatialCapabilities) return
        Log.i(TAG, "requesting Full Space (hasWindowFocus=${hasWindowFocus()})")
        runCatching { s.scene.requestFullSpace() }.onFailure { Log.w(TAG, "requestFullSpace failed", it) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) requestFullSpace()
    }

    private fun configureSession(s: Session): String {
        val base = Config.Builder(s.config)
            .setDeviceTracking(DeviceTrackingMode.SPATIAL)
            .setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL)
        // Depth improves hit tests on non-planar objects but is optional.
        val attempts = listOf(
            "head+planes+depth" to { base.setDepthEstimation(DepthEstimationMode.SMOOTH_ONLY).build() },
            "head+planes" to { base.setDepthEstimation(DepthEstimationMode.DISABLED).build() },
            "head only" to {
                base.setPlaneTracking(PlaneTrackingMode.DISABLED)
                    .setDepthEstimation(DepthEstimationMode.DISABLED).build()
            },
        )
        for ((name, build) in attempts) {
            try {
                val r = s.configure(build())
                if (r is SessionConfigureSuccess) return "tracking = $name"
                Log.w(TAG, "configure $name -> $r")
            } catch (e: Exception) {
                Log.w(TAG, "configure $name failed", e)
            }
        }
        return "session configure failed"
    }

    private fun toggleRun() {
        requestFullSpace()
        if (state.running) {
            client.disconnect()
            camera?.stop()
            highlights?.clear()
            pendingFrames.clear()
            state.running = false
            state.connectionMessage = "Stopped"
        } else {
            val cam = camera ?: run {
                state.cameraStatus = "camera not ready (permissions?)"
                return
            }
            state.save()
            client.updateConfig(state.labels, state.confidence)
            client.connect(state.serverUrl.trim())
            cam.start { desc -> state.cameraStatus = desc }
            state.running = true
        }
    }

    private fun applyLabels() {
        state.save()
        highlights?.clear()
        client.updateConfig(state.labels, state.confidence)
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "ハイライトしたい物を言ってください")
        }
        runCatching { voiceLauncher.launch(intent) }
            .onFailure { state.connectionMessage = "音声入力が利用できません: ${it.message}" }
    }

    private fun handleResult(result: DetectionResult) {
        val meta = pendingFrames.remove(result.frameId) ?: return
        // Drop metadata of frames that will never be answered.
        pendingFrames.keys.removeAll { it < result.frameId }

        val now = SystemClock.elapsedRealtime()
        if (lastResultAt > 0) {
            val inst = 1000f / (now - lastResultAt).coerceAtLeast(1)
            state.fps = state.fps * 0.9f + inst * 0.1f
        }
        lastResultAt = now
        state.latencyMs = state.latencyMs * 0.8f + (now - meta.sentAtMs) * 0.2f
        state.inferMs = state.inferMs * 0.8f + result.inferMs * 0.2f
        state.lastDetections = result.detections

        highlights?.onResult(result, meta)
    }

    override fun onDestroy() {
        state.save()
        client.disconnect()
        camera?.shutdown()
        highlights?.dispose()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
