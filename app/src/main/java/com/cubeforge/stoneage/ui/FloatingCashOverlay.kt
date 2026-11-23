package com.cubeforge.stoneage.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * Zero-allocation object-pooled floating cash & feedback popup overlay.
 */
class FloatingCashOverlay(context: Context) : View(context) {

    companion object {
        const val STYLE_CASH = 0
        const val STYLE_UPGRADE = 1
        const val STYLE_ANOMALY = 2
        const val STYLE_OVERCLOCK = 3
        private const val POOL_SIZE = 24
    }

    private class PopupSlot {
        var active: Boolean = false
        var text: String = ""
        var x: Float = 0f
        var y: Float = 0f
        var elapsed: Float = 0f
        var duration: Float = 1.05f
        var style: Int = STYLE_CASH
    }

    private val pool = Array(POOL_SIZE) { PopupSlot() }
    private var cursor = 0
    private var lastFrameMs = 0L

    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        color = Color.argb(210, 20, 16, 12)
    }

    init {
        isClickable = false
        isFocusable = false
    }

    fun spawnAtPx(text: String, xPx: Float, yPx: Float, style: Int = STYLE_CASH) {
        val slot = pool[cursor]
        cursor = (cursor + 1) % POOL_SIZE
        slot.active = true
        slot.text = text
        slot.x = xPx
        slot.y = yPx
        slot.elapsed = 0f
        slot.duration = if (style == STYLE_ANOMALY || style == STYLE_OVERCLOCK) 1.35f else 1.05f
        slot.style = style
        lastFrameMs = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    fun spawnNormalized(text: String, normX: Float, normY: Float, style: Int = STYLE_CASH) {
        val w = width.coerceAtLeast(resources.displayMetrics.widthPixels)
        val h = height.coerceAtLeast(resources.displayMetrics.heightPixels)
        spawnAtPx(text, normX * w, normY * h, style)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastFrameMs == 0L) 0.016f else ((now - lastFrameMs) / 1000f).coerceIn(0.004f, 0.08f)
        lastFrameMs = now

        var anyActive = false
        for (i in 0 until POOL_SIZE) {
            val s = pool[i]
            if (!s.active) continue
            s.elapsed += dt
            if (s.elapsed >= s.duration) {
                s.active = false
                continue
            }
            anyActive = true
            val u = (s.elapsed / s.duration).coerceIn(0f, 1f)
            val rise = u * 64f * density
            val alpha = ((1f - u * u) * 255f).toInt().coerceIn(0, 255)
            val spSize = when (s.style) {
                STYLE_ANOMALY, STYLE_OVERCLOCK -> 18f
                STYLE_UPGRADE -> 14.5f
                else -> 15.5f
            }
            textPaint.textSize = spSize * density
            strokePaint.textSize = spSize * density

            textPaint.color = when (s.style) {
                STYLE_ANOMALY -> Color.rgb(255, 222, 89)
                STYLE_OVERCLOCK -> Color.rgb(255, 145, 56)
                STYLE_UPGRADE -> Color.rgb(134, 239, 172)
                else -> Color.rgb(255, 232, 130)
            }
            textPaint.alpha = alpha
            strokePaint.alpha = (alpha * 0.85f).toInt()

            val drawY = s.y - rise
            canvas.drawText(s.text, s.x, drawY, strokePaint)
            canvas.drawText(s.text, s.x, drawY, textPaint)
        }

        if (anyActive) {
            postInvalidateOnAnimation()
        }
    }
}
