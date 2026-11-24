package com.example.xrhighlight

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI-observable state + persisted settings. */
class AppState(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl by mutableStateOf(prefs.getString("serverUrl", "ws://192.168.0.10:8765/ws")!!)
    var labelsText by mutableStateOf(prefs.getString("labels", "cup, bottle, keyboard")!!)
    var confidence by mutableFloatStateOf(prefs.getFloat("conf", 0.25f))
    var calibration by mutableStateOf(
        Calibration(
            yawDeg = prefs.getFloat("yaw", 0f),
            pitchDeg = prefs.getFloat("pitch", 0f),
            rollDeg = prefs.getFloat("roll", 0f),
            offsetX = prefs.getFloat("offX", 0f),
            offsetY = prefs.getFloat("offY", 0f),
            offsetZ = prefs.getFloat("offZ", 0f),
            fovScale = prefs.getFloat("fovScale", 1f),
            defaultDistance = prefs.getFloat("defDist", 1.5f),
        )
    )

    // Runtime status (not persisted)
    var running by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var connectionMessage by mutableStateOf("Not connected")
    var xrStatus by mutableStateOf("XR: initializing…")
    var cameraStatus by mutableStateOf("camera: not started")
    var fps by mutableFloatStateOf(0f)
    var latencyMs by mutableFloatStateOf(0f)
    var inferMs by mutableFloatStateOf(0f)
    var visibleHighlights by mutableIntStateOf(0)
    var lastDetections by mutableStateOf<List<Detection>>(emptyList())

    val labels: List<String> get() = parseLabels(labelsText)

    fun save() {
        val c = calibration
        prefs.edit()
            .putString("serverUrl", serverUrl)
            .putString("labels", labelsText)
            .putFloat("conf", confidence)
            .putFloat("yaw", c.yawDeg)
            .putFloat("pitch", c.pitchDeg)
            .putFloat("roll", c.rollDeg)
            .putFloat("offX", c.offsetX)
            .putFloat("offY", c.offsetY)
            .putFloat("offZ", c.offsetZ)
            .putFloat("fovScale", c.fovScale)
            .putFloat("defDist", c.defaultDistance)
            .apply()
    }
}
