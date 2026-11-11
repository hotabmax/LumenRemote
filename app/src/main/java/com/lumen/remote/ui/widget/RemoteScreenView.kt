package com.lumen.remote.ui.widget

import android.content.Context
import android.graphics.Outline
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * Экран партнёра: показывает кадр с сохранением пропорций и преобразует
 * координаты касания в нормализованные координаты удалённого экрана.
 */
class RemoteScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    init {
        scaleType = ScaleType.FIT_CENTER
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = resources.displayMetrics.density * 18f
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    /** Координаты отображения картинки внутри View (FIT_CENTER). */
    private fun contentRect(): FloatArray? {
        val drawable = drawable ?: return null
        val dw = drawable.intrinsicWidth.toFloat()
        val dh = drawable.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return null
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (availableWidth <= 0f || availableHeight <= 0f) return null
        val scale = min(availableWidth / dw, availableHeight / dh)
        val drawWidth = dw * scale
        val drawHeight = dh * scale
        val left = paddingLeft + (availableWidth - drawWidth) / 2f
        val top = paddingTop + (availableHeight - drawHeight) / 2f
        return floatArrayOf(left, top, drawWidth, drawHeight)
    }

    /** Нормализованная точка (0..1) для координат касания или null вне изображения. */
    fun normalizedPoint(x: Float, y: Float): PointF? {
        val rect = contentRect() ?: return null
        val nx = (x - rect[0]) / max(rect[2], 1f)
        val ny = (y - rect[1]) / max(rect[3], 1f)
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        return PointF(nx, ny)
    }
}
