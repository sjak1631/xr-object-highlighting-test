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

    fun bind(label: String, score: Float) {
        if (label == this.label && kotlin.math.abs(score - this.score) < 0.02f) return
        this.label = label
        this.score = score
        this.color = labelColor(label)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
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
