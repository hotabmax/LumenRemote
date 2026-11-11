package com.lumen.remote.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lumen.remote.R
import kotlin.math.abs

/**
 * Плавающая кнопка «Стоп» поверх остальных окон.
 *
 * Показывается, пока к телефону подключён партнёр: владелец устройства может
 * прекратить трансляцию, не возвращаясь в приложение. Кнопку можно перетащить,
 * чтобы она не закрывала важное место на экране.
 */
class StreamOverlay(
    private val context: Context,
    private val onStop: () -> Unit
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var button: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** Выдано ли разрешение «поверх других приложений». */
    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** Показывает кнопку, если разрешение есть и её ещё нет на экране. */
    fun show() {
        if (button != null) return
        if (!canShow()) {
            Log.w(TAG, "Нет разрешения «поверх других приложений»")
            return
        }

        val view = buildButton()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(EDGE_DP)
            y = startY()
        }

        view.setOnClickListener { onStop() }
        enableDrag(view, params)

        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) {
            Log.w(TAG, "Не удалось показать кнопку остановки")
            return
        }
        button = view
        layoutParams = params

        // Ставим у правого края, когда известна фактическая ширина кнопки.
        view.post {
            val screen = context.resources.displayMetrics.widthPixels
            if (button !== view) return@post
            params.x = (screen - view.width - dp(EDGE_DP)).coerceAtLeast(dp(EDGE_DP))
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        Log.i(TAG, "Кнопка остановки трансляции показана")
    }

    /** Убирает кнопку с экрана. */
    fun hide() {
        val view = button ?: return
        button = null
        layoutParams = null
        runCatching { windowManager.removeView(view) }
        Log.i(TAG, "Кнопка остановки трансляции скрыта")
    }

    /** Собирает «пилюлю»: красный фон, иконка и слово «Стоп». */
    private fun buildButton(): View {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(18), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(ContextCompat.getColor(context, R.color.danger))
            }
            elevation = dp(8).toFloat()
            isClickable = true
            contentDescription = context.getString(R.string.overlay_stop)
        }

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_stop)
            setColorFilter(Color.WHITE)
        }
        container.addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)))

        val label = TextView(context).apply {
            text = context.getString(R.string.overlay_stop_short)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(8), 0, 0, 0)
        }
        container.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return container
    }

    /** Перетаскивание кнопки: короткое касание остаётся кликом. */
    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var fromX = 0
        var fromY = 0
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    fromX = params.x
                    fromY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && abs(dx) + abs(dy) > slop) dragging = true
                    if (dragging) {
                        params.x = fromX + dx
                        params.y = fromY + dy
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun startY(): Int {
        val metrics = context.resources.displayMetrics
        val target = (metrics.heightPixels * START_Y_RATIO).toInt()
        return target.coerceIn(dp(EDGE_DP), metrics.heightPixels / 2)
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "LumenOverlay"
        const val EDGE_DP = 16
        const val START_Y_RATIO = 0.45f
    }
}
