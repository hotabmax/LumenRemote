package com.lumen.remote.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lumen.remote.net.Proto

/**
 * Служба удалённого управления: выполняет касания, свайпы, системные клавиши
 * и ввод текста, полученные от подключённого партнёра.
 */
class RemoteControlService : AccessibilityService() {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun tap(normalizedX: Float, normalizedY: Float) {
        val size = screenSize()
        val x = normalizedX.coerceIn(0f, 1f) * size.x
        val y = normalizedY.coerceIn(0f, 1f) * size.y
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 60)
    }

    fun swipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, durationMs: Int) {
        val size = screenSize()
        val path = Path().apply {
            moveTo(nx1.coerceIn(0f, 1f) * size.x, ny1.coerceIn(0f, 1f) * size.y)
            lineTo(nx2.coerceIn(0f, 1f) * size.x, ny2.coerceIn(0f, 1f) * size.y)
        }
        dispatch(path, durationMs.coerceIn(40, 1200))
    }

    fun pressKey(key: String) {
        val action = when (key) {
            Proto.KEY_BACK -> GLOBAL_ACTION_BACK
            Proto.KEY_HOME -> GLOBAL_ACTION_HOME
            Proto.KEY_RECENTS -> GLOBAL_ACTION_RECENTS
            Proto.KEY_NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return
        }
        mainHandler.post { runCatching { performGlobalAction(action) } }
    }

    /** Дописывает текст в активное поле ввода. */
    fun typeText(value: String): Boolean {
        if (value.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val current = focused.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                current + value
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun dispatch(path: Path, durationMs: Int) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.toLong())
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        mainHandler.post {
            val accepted = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
            markGestureResult(accepted)
            if (!accepted) {
                Log.w(TAG, "dispatchGesture отклонён системой (жест не выполнен)")
            }
        }
    }

    /**
     * Реальный размер дисплея: `resources.displayMetrics` у службы на части прошивок
     * (Huawei/HarmonyOS, gesture navigation) не совпадает с экраном захвата.
     */
    private fun screenSize(): Point {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return Point(bounds.width(), bounds.height())
            }
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    companion object {
        private const val TAG = "LumenControl"

        @Volatile
        var instance: RemoteControlService? = null
            private set

        /** Последний жест отклонён системой (диагностика на экране приёма). */
        @Volatile
        var lastGestureRejected: Boolean = false
            private set

        fun markGestureResult(accepted: Boolean) {
            lastGestureRejected = !accepted
        }

        val isRunning: Boolean get() = instance != null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, RemoteControlService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
