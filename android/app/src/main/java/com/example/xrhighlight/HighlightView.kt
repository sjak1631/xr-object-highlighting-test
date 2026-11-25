package com.example.xrhighlight

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * Content of one highlight panel: a glowing frame with corner brackets and a label chip.
 *
 * XREAL Aura is an optical see-through (additive) display: black is transparent, so we only draw
 * bright strokes and a faint tint and keep everything else transparent.
 */
enum class MarkerStyle { PIN, BOX }

/** Height of the pin's label chip relative to the panel width (see HighlightController). */
const val PIN_CHIP_RATIO = 0.2f

class HighlightView(context: Context) : View(context) {
    private var label = ""
    private var score = 0f
    private var color = Color.CYAN

    private val density = resources.displayMetrics.density
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        isFakeBoldText = true
    }
    private val rect = RectF()

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    private var markerStyle = MarkerStyle.PIN

    fun bind(label: String, score: Float, style: MarkerStyle = markerStyle) {
        if (label == this.label && style == markerStyle && kotlin.math.abs(score - this.score) < 0.02f) return
        this.label = label
        this.score = score
        this.markerStyle = style
        this.color = labelColor(label)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (markerStyle == MarkerStyle.PIN) drawPin(canvas, w, h) else drawBox(canvas, w, h)
    }

    /**
     * Game-style marker: label chip at the top, a vertical leader line, and a ring at the bottom
     * edge of the panel, which is placed exactly on the object. Only that one point has to land on
     * the object, so it tolerates position/size errors much better than a bounding box.
     *
     * Geometry contract with [HighlightController]: the chip is [PIN_CHIP_RATIO] * width tall.
     */
    private fun drawPin(canvas: Canvas, w: Float, h: Float) {
        val chipH = w * PIN_CHIP_RATIO
        val cx = w / 2f
        val ringR = w * 0.035f
        val lineW = w * 0.012f

        // Label chip (centered, auto-width)
        text.textSize = chipH * 0.62f
        val caption = label
        val pad = chipH * 0.35f
        val chipW = min(text.measureText(caption) + 2 * pad, w)
        val r = chipH * 0.5f
        chip.color = color
        rect.set(cx - chipW / 2, 0f, cx + chipW / 2, chipH)
        glow.color = withAlpha(color, 70)
        glow.strokeWidth = lineW * 3f
        canvas.drawRoundRect(rect, r, r, glow)
        canvas.drawRoundRect(rect, r, r, chip)
        canvas.drawText(caption, rect.left + pad, chipH / 2 - (text.descent() + text.ascent()) / 2, text)

        // Leader line
        val lineTop = chipH
        val lineBottom = h - ringR * 2f
        if (lineBottom > lineTop) {
            glow.strokeWidth = lineW * 3f
            canvas.drawLine(cx, lineTop, cx, lineBottom, glow)
            stroke.color = color
            stroke.strokeWidth = lineW
            canvas.drawLine(cx, lineTop, cx, lineBottom, stroke)
        }

        // Target ring on the object
        val ringY = h - ringR - lineW
        glow.strokeWidth = lineW * 3f
        canvas.drawCircle(cx, ringY, ringR, glow)
        stroke.strokeWidth = lineW * 1.5f
        canvas.drawCircle(cx, ringY, ringR, stroke)
        fill.color = color
        canvas.drawCircle(cx, ringY, ringR * 0.35f, fill)
    }

    private fun drawBox(canvas: Canvas, w: Float, h: Float) {
        val base = min(w, h)
        val sw = (base * 0.025f).coerceIn(2f * density, 8f * density)
        val inset = sw * 2.5f
        rect.set(inset, inset, w - inset, h - inset)
        val radius = base * 0.06f

        fill.color = withAlpha(color, 28)
        canvas.drawRoundRect(rect, radius, radius, fill)

        glow.color = withAlpha(color, 70)
        glow.strokeWidth = sw * 3.5f
        canvas.drawRoundRect(rect, radius, radius, glow)

        stroke.color = color
        stroke.strokeWidth = sw
        canvas.drawRoundRect(rect, radius, radius, stroke)

        // Corner brackets make small objects easier to spot.
        stroke.strokeWidth = sw * 2f
        val c = min(rect.width(), rect.height()) * 0.22f
        canvas.drawLine(rect.left, rect.top, rect.left + c, rect.top, stroke)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + c, stroke)
        canvas.drawLine(rect.right, rect.top, rect.right - c, rect.top, stroke)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + c, stroke)
        canvas.drawLine(rect.left, rect.bottom, rect.left + c, rect.bottom, stroke)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - c, stroke)
        canvas.drawLine(rect.right, rect.bottom, rect.right - c, rect.bottom, stroke)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - c, stroke)

        if (label.isNotEmpty()) {
            text.textSize = (base * 0.11f).coerceIn(10f * density, 28f * density)
            val caption = "$label ${(score * 100).toInt()}%"
            val tw = text.measureText(caption)
            val pad = text.textSize * 0.35f
            val chipW = min(tw + 2 * pad, rect.width())
            val chipH = text.textSize + 2 * pad * 0.6f
            chip.color = color
            canvas.drawRoundRect(
                rect.left, rect.top, rect.left + chipW, rect.top + chipH, radius, radius, chip,
            )
            canvas.drawText(caption, rect.left + pad, rect.top + chipH - pad * 0.9f, text)
        }
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
