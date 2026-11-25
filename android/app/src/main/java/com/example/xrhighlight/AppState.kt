package com.example.xrhighlight

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.xr.runtime.math.Quaternion
import org.json.JSONArray
import org.json.JSONObject

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

    var useAutoCalibration by mutableStateOf(prefs.getBoolean("useAuto", true))
    var markerStyle by mutableStateOf(
        runCatching { MarkerStyle.valueOf(prefs.getString("marker", "PIN")!!) }.getOrDefault(MarkerStyle.PIN)
    )
    /** Last converged auto calibration is persisted, so it is reused after a restart. */
    var autoCalibration by mutableStateOf(loadAutoCalibration())

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
            .putBoolean("useAuto", useAutoCalibration)
            .putString("marker", markerStyle.name)
            .putString("autoCalib", autoCalibration?.takeIf { it.converged }?.let(::toJson))
            .apply()
    }

    private fun toJson(c: AutoCalibration): String = JSONObject()
        .put("q", JSONArray(listOf(c.rotation.x, c.rotation.y, c.rotation.z, c.rotation.w).map { it.toDouble() }))
        .put("tan_h", c.camera.tanHalfH.toDouble()).put("tan_v", c.camera.tanHalfV.toDouble())
        .put("cx", c.camera.cx.toDouble()).put("cy", c.camera.cy.toDouble())
        .put("k1", c.camera.k1.toDouble()).put("k2", c.camera.k2.toDouble())
        .put("dt_ms", c.timeOffsetMs.toDouble()).put("rms_px", c.rmsPx.toDouble())
        .put("pairs", c.pairs).put("quality", c.quality.toDouble())
        .put("euler", JSONArray(c.eulerDeg.map { it.toDouble() }))
        .put("hfov", c.hfovDeg.toDouble()).put("vfov", c.vfovDeg.toDouble())
        .toString()

    private fun loadAutoCalibration(): AutoCalibration? = try {
        prefs.getString("autoCalib", null)?.let { str ->
            val j = JSONObject(str)
            val q = j.getJSONArray("q")
            val e = j.getJSONArray("euler")
            AutoCalibration(
                rotation = Quaternion(
                    q.getDouble(0).toFloat(), q.getDouble(1).toFloat(),
                    q.getDouble(2).toFloat(), q.getDouble(3).toFloat(),
                ),
                camera = CameraModel(
                    j.getDouble("tan_h").toFloat(), j.getDouble("tan_v").toFloat(),
                    j.getDouble("cx").toFloat(), j.getDouble("cy").toFloat(),
                    j.optDouble("k1", 0.0).toFloat(), j.optDouble("k2", 0.0).toFloat(),
                ),
                timeOffsetMs = j.getDouble("dt_ms").toFloat(),
                rmsPx = j.getDouble("rms_px").toFloat(),
                pairs = j.getInt("pairs"),
                quality = j.getDouble("quality").toFloat(),
                converged = true,
                eulerDeg = (0 until e.length()).map { e.getDouble(it).toFloat() },
                hfovDeg = j.getDouble("hfov").toFloat(),
                vfovDeg = j.getDouble("vfov").toFloat(),
            )
        }
    } catch (e: Exception) {
        null
    }
}
