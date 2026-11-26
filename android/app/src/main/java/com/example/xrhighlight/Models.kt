package com.example.xrhighlight

import android.graphics.Color
import java.util.zip.CRC32

/** One detection from the server. Box is normalized to [0, 1] in image coordinates (y down). */
data class Detection(
    val label: String,
    val score: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
) {
    val cx get() = (x1 + x2) * 0.5f
    val cy get() = (y1 + y2) * 0.5f
    val w get() = x2 - x1
    val h get() = y2 - y1
}

data class DetectionResult(
    val frameId: Long,
    val inferMs: Float,
    val detections: List<Detection>,
)

/** Same label -> hue mapping as server/webcam_demo.py so colors match across tools. */
fun labelColor(label: String): Int {
    val crc = CRC32().apply { update(label.toByteArray(Charsets.UTF_8)) }.value
    val hue = (crc % 360).toFloat()
    return Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
}

/** "cup, ボトル、keyboard" -> ["cup", "ボトル", "keyboard"] */
fun parseLabels(text: String): List<String> =
    text.split(',', '、', '，', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
