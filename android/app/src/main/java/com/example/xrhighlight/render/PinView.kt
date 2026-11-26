package com.example.xrhighlight.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.example.xrhighlight.labelColor
import kotlin.math.min

/**
 * Game-style marker drawn into a panel: label chip at the top, a vertical leader line, and a ring
 * at the bottom edge (the bottom edge is placed on the object by [PinRenderer]).
 *
 * XREAL Aura is an additive see-through display (black = transparent), so only bright strokes are
 * drawn and the rest stays transparent.
 */
class PinView(context: Context) : View(context) {
    private var label = ""
    private var color = Color.CYAN

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        isFakeBoldText = true
    }
    private val rect = RectF()

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun bind(label: String) {
        if (label == this.label) return
        this.label = label
        this.color = labelColor(label)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val chipH = w * CHIP_RATIO
        val cx = w / 2f
        val ringR = w * 0.035f
        val lineW = w * 0.012f
        glow.color = withAlpha(color, 70)
        glow.strokeWidth = lineW * 3f
        stroke.color = color

        // Label chip (centered, auto-width)
        text.textSize = chipH * 0.62f
        val pad = chipH * 0.35f
        val chipW = min(text.measureText(label) + 2 * pad, w)
        val r = chipH * 0.5f
        rect.set(cx - chipW / 2, 0f, cx + chipW / 2, chipH)
        fill.color = color
        canvas.drawRoundRect(rect, r, r, glow)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawText(label, rect.left + pad, chipH / 2 - (text.descent() + text.ascent()) / 2, text)

        // Leader line
        val lineBottom = h - ringR * 2f
        if (lineBottom > chipH) {
            canvas.drawLine(cx, chipH, cx, lineBottom, glow)
            stroke.strokeWidth = lineW
            canvas.drawLine(cx, chipH, cx, lineBottom, stroke)
        }

        // Ring on the object
        val ringY = h - ringR - lineW
        canvas.drawCircle(cx, ringY, ringR, glow)
        stroke.strokeWidth = lineW * 1.5f
        canvas.drawCircle(cx, ringY, ringR, stroke)
        canvas.drawCircle(cx, ringY, ringR * 0.35f, fill)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        /** Height of the label chip relative to the panel width (layout contract with PinRenderer). */
        const val CHIP_RATIO = 0.2f
    }
}
