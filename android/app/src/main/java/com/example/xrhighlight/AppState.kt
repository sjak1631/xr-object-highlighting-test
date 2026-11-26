package com.example.xrhighlight

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.xrhighlight.geometry.AutoCalibration
import org.json.JSONObject

/** UI-observable state + persisted settings. */
class AppState(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Settings (persisted)
    var serverUrl by mutableStateOf(prefs.getString("serverUrl", "ws://127.0.0.1:8765/ws")!!)
    var labelsText by mutableStateOf(prefs.getString("labels", "cup, bottle, keyboard")!!)
    var confidence by mutableFloatStateOf(prefs.getFloat("conf", 0.25f))
    /** Latest auto calibration; the last converged one is persisted and reused after restarts. */
    var calibration by mutableStateOf(
        runCatching { AutoCalibration.fromJson(JSONObject(prefs.getString("autoCalib", null)!!)) }.getOrNull()
    )

    // Runtime status
    var running by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var connectionMessage by mutableStateOf("Not connected")
    var xrStatus by mutableStateOf("XR: initializing…")
    var cameraStatus by mutableStateOf("camera: not started")
    var fps by mutableFloatStateOf(0f)
    var latencyMs by mutableFloatStateOf(0f)
    var inferMs by mutableFloatStateOf(0f)
    var visibleObjects by mutableIntStateOf(0)
    var lastDetections by mutableStateOf<List<Detection>>(emptyList())

    val labels: List<String> get() = parseLabels(labelsText)

    /** Converged calibration to use for geometry, if any. */
    val activeCalibration: AutoCalibration? get() = calibration?.takeIf { it.converged }

    fun save() {
        prefs.edit()
            .putString("serverUrl", serverUrl)
            .putString("labels", labelsText)
            .putFloat("conf", confidence)
            .putString("autoCalib", activeCalibration?.toJson()?.toString())
            .apply()
    }
}
