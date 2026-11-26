package com.example.xrhighlight.xr

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.Config
import androidx.xr.runtime.DepthEstimationMode
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.ExperimentalSpatialAnnotationsApi
import androidx.xr.runtime.PlaneTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.SpatialAnnotationTrackingMode
import androidx.xr.runtime.math.Pose
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.scene
import kotlinx.coroutines.launch

/**
 * Owns the Jetpack XR session: creation, configuration (head tracking, planes, depth), Full Space,
 * and recording of head poses into [poseHistory] (same clock as camera timestamps).
 */
class XrEnvironment(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val onStatus: (String) -> Unit,
) {
    var session: Session? = null
        private set
    val poseHistory = PoseHistory()
    private var arDevice: ArDevice? = null
    private var trackingStatus = ""

    /** Creates and configures the session. Returns null on non-XR devices. */
    suspend fun start(enableSpatialAnnotations: Boolean = false): Session? {
        val result = Session.create(activity)
        if (result !is SessionCreateSuccess) {
            onStatus("XR: unavailable (${result::class.simpleName}) – 2D test mode")
            return null
        }
        val s = result.session
        session = s
        trackingStatus = configure(s)
        if (enableSpatialAnnotations) trackingStatus += " | annotations: ${enableSpatialAnnotations(s)}"
        s.scene.addSpatialCapabilitiesChangedListener { publishStatus() }
        publishStatus()
        requestFullSpace()

        val device = ArDevice.getInstance(s)
        arDevice = device
        scope.launch {
            device.state.collect { st ->
                if (st.trackingState == TrackingState.TRACKING) {
                    poseHistory.add(SystemClock.elapsedRealtimeNanos(), st.devicePose)
                }
            }
        }
        return s
    }

    /** Current head pose (perception space), or null while tracking is lost. */
    fun headPose(): Pose? {
        val st = arDevice?.state?.value ?: return null
        return if (st.trackingState == TrackingState.TRACKING) st.devicePose else null
    }

    /** Panels can only be placed freely in Full Space; only honoured while the activity has focus. */
    fun requestFullSpace() {
        val s = session ?: return
        if (SpatialCapability.SPATIAL_UI in s.scene.spatialCapabilities) return
        runCatching { s.scene.requestFullSpace() }.onFailure { Log.w(TAG, "requestFullSpace failed", it) }
    }

    private fun configure(s: Session): String {
        val base = Config.Builder(s.config)
            .setDeviceTracking(DeviceTrackingMode.SPATIAL)
            .setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL)
        // Use as much as the device supports.
        val attempts = listOf(
            "head+planes+depth" to { base.setDepthEstimation(DepthEstimationMode.SMOOTH_ONLY).build() },
            "head+planes" to { base.setDepthEstimation(DepthEstimationMode.DISABLED).build() },
            "head only" to {
                base.setPlaneTracking(PlaneTrackingMode.DISABLED).setDepthEstimation(DepthEstimationMode.DISABLED).build()
            },
        )
        for ((name, build) in attempts) {
            try {
                if (s.configure(build()) is SessionConfigureSuccess) return "tracking = $name"
            } catch (e: Exception) {
                Log.w(TAG, "configure $name failed", e)
            }
        }
        return "session configure failed"
    }

    /** Experimental system-side tracking of image regions (see locate/SpatialAnnotationProbe). */
    @OptIn(ExperimentalSpatialAnnotationsApi::class)
    private fun enableSpatialAnnotations(s: Session): String = try {
        val r = s.configure(
            Config.Builder(s.config).setSpatialAnnotationTracking(SpatialAnnotationTrackingMode.QUAD).build()
        )
        if (r is SessionConfigureSuccess) "QUAD enabled" else "unsupported (${r::class.simpleName})"
    } catch (e: Throwable) {
        "unsupported (${e::class.simpleName})"
    }

    private fun publishStatus() {
        val caps = session?.scene?.spatialCapabilities ?: return
        val names = buildList {
            if (SpatialCapability.SPATIAL_UI in caps) add("UI")
            if (SpatialCapability.SPATIAL_3D_CONTENT in caps) add("3D")
            if (SpatialCapability.PASSTHROUGH_CONTROL in caps) add("passthrough")
        }.ifEmpty { listOf("none") }.joinToString("+")
        val warning = if (SpatialCapability.SPATIAL_UI !in caps) " (Full Space ではないため表示できません)" else ""
        onStatus("XR: $trackingStatus | spatial=$names$warning")
    }

    companion object {
        private const val TAG = "XrEnvironment"
    }
}
