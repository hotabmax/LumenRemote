package com.lumen.remote.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.animation.ValueAnimator
import androidx.core.content.ContextCompat
import com.lumen.remote.R
import com.lumen.remote.util.dp
import kotlin.math.min

/**
 * Декоративный радар: расходящиеся волны вокруг центрального источника.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.4f).toFloat()
        color = ContextCompat.getColor(context, R.color.cyan)
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.violet)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringCount = 3
    private var progress = 0f
    private var animator: ValueAnimator? = null

    var running: Boolean = true
        set(value) {
            field = value
            if (value) start() else stop()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val minSize = min(width, height).toFloat()
        val maxRadius = minSize / 2f - context.dp(4f)
        if (maxRadius <= 0f) return

        if (glowPaint.shader == null || glowRadius != maxRadius) {
            glowRadius = maxRadius
            glowPaint.shader = RadialGradient(
                cx, cy, maxRadius,
                intArrayOf(
                    withAlpha(ContextCompat.getColor(context, R.color.violet), 90),
                    withAlpha(ContextCompat.getColor(context, R.color.violet), 0)
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        glowPaint.shader?.let { canvas.drawCircle(cx, cy, maxRadius, glowPaint) }

        for (i in 0 until ringCount) {
            val t = ((progress + i.toFloat() / ringCount) % 1f + 1f) % 1f
            val radius = maxRadius * (0.18f + 0.82f * t)
            ringPaint.alpha = ((1f - t) * 120).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }

        corePaint.alpha = 190
        canvas.drawCircle(cx, cy, maxRadius * 0.13f, corePaint)
        corePaint.alpha = 255
        canvas.drawCircle(cx, cy, maxRadius * 0.055f, corePaint)
    }

    private var glowRadius = -1f

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
