package com.example.xrhighlight

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client for server/server.py.
 *
 * Frames are sent stop-and-wait: a new frame is only sent once the result for the previous one
 * arrived (or [IN_FLIGHT_TIMEOUT_MS] passed). This keeps end-to-end latency at one round trip
 * instead of letting a queue build up on a slow link.
 */
class DetectionClient(
    private val scope: CoroutineScope,
    private val onResult: (DetectionResult) -> Unit,
    private val onStatus: (connected: Boolean, message: String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile var isConnected = false
        private set
    @Volatile private var wanted = false
    private var url = ""
    @Volatile private var labels: List<String> = emptyList()
    @Volatile private var conf = 0.25f
    private var reconnectJob: Job? = null

    private val inFlight = AtomicBoolean(false)
    @Volatile private var inFlightSince = 0L

    fun connect(url: String) {
        disconnect()
        this.url = url
        wanted = true
        open()
    }

    fun disconnect() {
        wanted = false
        reconnectJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
        isConnected = false
        inFlight.set(false)
    }

    fun updateConfig(labels: List<String>, conf: Float) {
        this.labels = labels
        this.conf = conf
        ws?.takeIf { isConnected }?.send(configJson())
    }

    /** Returns true if the caller may send a frame now. Must be followed by [sendFrame] or [releaseSlot]. */
    fun tryAcquireSlot(): Boolean {
        if (!isConnected || labels.isEmpty()) return false
        val now = SystemClock.elapsedRealtime()
        if (inFlight.get() && now - inFlightSince > IN_FLIGHT_TIMEOUT_MS) inFlight.set(false)
        if (!inFlight.compareAndSet(false, true)) return false
        inFlightSince = now
        return true
    }

    fun releaseSlot() = inFlight.set(false)

    fun sendFrame(frameId: Long, jpeg: ByteArray) {
        val buf = ByteBuffer.allocate(8 + jpeg.size).putLong(frameId).put(jpeg)
        val ok = ws?.send(buf.array().toByteString()) ?: false
        if (!ok) releaseSlot()
    }

    private fun configJson(): String = JSONObject()
        .put("type", "config")
        .put("labels", JSONArray(labels))
        .put("conf", conf.toDouble())
        .toString()

    private fun open() {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            onStatus(false, "Invalid URL: $url")
            return
        }
        onStatus(false, "Connecting to $url …")
        ws = http.newWebSocket(request, listener)
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (wanted && !isConnected) open()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== ws) return
            isConnected = true
            inFlight.set(false)
            webSocket.send(configJson())
            onStatus(true, "Connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            try {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "detections" -> {
                        inFlight.set(false)
                        onResult(parseResult(json))
                    }
                    "config_ack" -> Log.i(TAG, "server labels: ${json.optJSONArray("labels")}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "bad message: $text", e)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            isConnected = false
            onStatus(false, "Closed ($code)")
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            isConnected = false
            onStatus(false, "Connection failed: ${t.message}")
            scheduleReconnect()
        }
    }

    private fun parseResult(json: JSONObject): DetectionResult {
        val arr = json.getJSONArray("detections")
        val dets = (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val b = d.getJSONArray("box")
            Detection(
                label = d.getString("label"),
                score = d.getDouble("score").toFloat(),
                x1 = b.getDouble(0).toFloat(),
                y1 = b.getDouble(1).toFloat(),
                x2 = b.getDouble(2).toFloat(),
                y2 = b.getDouble(3).toFloat(),
            )
        }
        return DetectionResult(
            frameId = json.getLong("frame_id"),
            inferMs = json.optDouble("infer_ms", 0.0).toFloat(),
            detections = dets,
        )
    }

    companion object {
        private const val TAG = "DetectionClient"
        private const val IN_FLIGHT_TIMEOUT_MS = 1500L
        private const val RECONNECT_DELAY_MS = 2000L
    }
}
