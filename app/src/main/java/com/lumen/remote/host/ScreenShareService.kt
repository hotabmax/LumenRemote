package com.lumen.remote.host

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lumen.remote.LumenApp
import com.lumen.remote.R
import com.lumen.remote.core.Prefs
import com.lumen.remote.net.Proto
import com.lumen.remote.ui.host.HostActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Передний сервис: захватывает экран через MediaProjection и отправляет JPEG-кадры партнёру.
 *
 * Устойчивость к поведению прошивок (Huawei/HarmonyOS, Xiaomi и др.):
 *  - плотность экрана масштабируется вместе с размером кадра (иначе часть прошивок
 *    отдаёт чёрное изображение);
 *  - учитывается `Image.cropRect` и выравнивание строк (rowStride);
 *  - VirtualDisplay пересоздаётся при смене размера контента и при остановке кадров;
 *  - во время трансляции удерживается CPU wake lock, экран удерживает Activity.
 */
class ScreenShareService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var paddedBitmap: Bitmap? = null
    private var frameBitmap: Bitmap? = null
    private val frameCanvas = Canvas()
    private val jpegBuffer = ByteArrayOutputStream(256 * 1024)

    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0
    private var sourceDensity = 0
    private var lastSentAt = 0L
    private var captureStartedAt = 0L
    private var everReceivedFrame = false
    private var recreateAttempts = 0
    private var firstFrameLogged = false
    private var releasing = false
    private var lastJpeg: ByteArray? = null

    /**
     * Стратегия поверхности. RGBA_8888 работает у большинства, но на части
     * EMUI/HarmonyOS буфер приходит пустым — вторая попытка идёт через
     * HardwareBuffer (ImageFormat.PRIVATE).
     */
    private var usePrivateFormat = false
    private var captureAttempt = 0
    private var h264Encoder: H264Encoder? = null
    private var h264Mode = false

    /** Кнопка «Остановить» поверх других окон и наблюдение за сеансом. */
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stopOverlay: StreamOverlay? = null
    private var overlayWatching = false

    /** Все размеры перебраны и ни один не дал кадров — больше не пробуем. */
    private var h264Unsupported = false

    /**
     * Медленный повтор последнего кадра: MediaProjection не отдаёт новые кадры
     * на статичном экране, а первый кадр на части прошивок может быть чёрным.
     * Повтор гарантирует, что у партнёра появится изображение.
     */
    private val frameKeepalive = object : Runnable {
        override fun run() {
            val handler = captureHandler ?: return
            if (releasing) return
            if (HostSession.isStreaming() &&
                SystemClock.elapsedRealtime() - lastSentAt > FRAME_KEEPALIVE_MS
            ) {
                lastJpeg?.let { HostSession.sendFrame(it) }
            }
            handler.postDelayed(this, FRAME_KEEPALIVE_MS)
        }
    }

    /**
     * Пересоздаёт поверхность, если кадров нет *вообще* (сбой старта) или если
     * контент вернулся из скрытого состояния. Тишина при статичном экране — норма:
     * MediaProjection отдаёт кадр только при изменении изображения.
     */
    private val stallWatchdog = object : Runnable {
        override fun run() {
            val handler = captureHandler ?: return
            if (releasing) return

            // Партнёр умеет H.264 — переключаемся сразу, не дожидаясь проблем с JPEG.
            if (!h264Mode && HostSession.wantsH264) switchToH264()

            val now = SystemClock.elapsedRealtime()
            val waitingFirstFrame = now - captureStartedAt
            if (!h264Mode &&
                HostSession.isStreaming() &&
                !everReceivedFrame &&
                waitingFirstFrame > FIRST_FRAME_TIMEOUT_MS &&
                recreateAttempts < MAX_RECREATE_ATTEMPTS
            ) {
                recreateAttempts += 1
                if (!usePrivateFormat && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    usePrivateFormat = true
                    Log.w(TAG, "Первый кадр не получен — пробую ImageFormat.PRIVATE (HardwareBuffer)")
                    recreateDisplay()
                } else {
                    Log.w(TAG, "Первый кадр не получен с обеих стратегий — ждём смены экрана")
                }
            }
            handler.postDelayed(this, STALL_CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                watchOverlay()
                startForegroundNotification()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != Activity.RESULT_OK || data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCapture(resultCode, data)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HostActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, LumenApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(getString(R.string.host_notification_title))
            .setContentText(getString(R.string.host_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.host_stop_notification), stopIntent)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (projection != null) return

        acquireWakeLock()

        val thread = HandlerThread("lumen-capture").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = manager.getMediaProjection(resultCode, data) ?: run {
            stopCapture()
            return
        }
        projection = mediaProjection

        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection остановлен системой")
                stopCapture()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width <= 0 || height <= 0) return
                val (newWidth, newHeight, newDensity) = targetCaptureSize(width, height)
                if (newWidth == captureWidth && newHeight == captureHeight) return
                captureWidth = newWidth
                captureHeight = newHeight
                captureDensity = newDensity
                Log.i(TAG, "Размер контента изменился: ${width}x$height → ${newWidth}x$newHeight")
                captureHandler?.post {
                    recreateDisplay()
                    HostSession.onScreenSize(captureWidth, captureHeight)
                }
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                Log.i(TAG, "Видимость транслируемого контента: $isVisible")
                if (!isVisible) {
                    // Экран выключен или контент скрыт: кадров не будет до возврата.
                    HostSession.onContentVisibilityChanged(false)
                } else {
                    HostSession.onContentVisibilityChanged(true)
                    captureHandler?.post { recreateDisplay() }
                }
            }
        }, handler)

        val metrics = displayMetricsForProjection()
        sourceDensity = metrics.densityDpi
        val (width, height, density) = targetCaptureSize(metrics.widthPixels, metrics.heightPixels)
        captureWidth = width
        captureHeight = height
        captureDensity = density

        Log.i(
            TAG,
            "Захват ${captureWidth}x$captureHeight @ ${captureDensity}dpi " +
                "(экран ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi}dpi)"
        )

        createDisplay()
        HostSession.onCaptureState(true)
        handler.postDelayed(stallWatchdog, STALL_CHECK_INTERVAL_MS)
        handler.postDelayed(frameKeepalive, FRAME_KEEPALIVE_MS)
    }

    /** Размер кадра и плотность масштабируются согласованно. */
    /**
     * Размер поверхности захвата — **нативный** размер экрана.
     *
     * Уменьшенный VirtualDisplay на части прошивок (Huawei/HarmonyOS, EMUI)
     * даёт пустой (чёрный) буфер, поэтому масштабируем только JPEG при кодировании.
     */
    private fun targetCaptureSize(sourceWidth: Int, sourceHeight: Int): Triple<Int, Int, Int> {
        val width = sourceWidth.coerceAtLeast(2)
        val height = sourceHeight.coerceAtLeast(2)
        val density = (if (sourceDensity > 0) sourceDensity else resources.configuration.densityDpi)
            .coerceIn(120, 640)
        return Triple(width, height, density)
    }

    /** Размер JPEG-кадра: ограничиваем длинную сторону для экономии трафика. */
    private fun jpegSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val longest = max(sourceWidth, sourceHeight).toFloat().coerceAtLeast(1f)
        val scale = min(1f, JPEG_MAX_DIMENSION / longest)
        return Pair(
            (sourceWidth * scale).toInt().coerceAtLeast(2),
            (sourceHeight * scale).toInt().coerceAtLeast(2)
        )
    }

    /**
     * Реальные размеры дисплея. `resources.displayMetrics` у Service-контекста
     * на части прошивок учитывает совместимость и системные панели, поэтому
     * используем метрики дисплея.
     */
    private fun displayMetricsForProjection(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
            metrics.density = resources.displayMetrics.density
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            metrics.setTo(resources.displayMetrics)
        }
        return metrics
    }

    private fun createDisplay() {
        val handler = captureHandler ?: return
        if (captureWidth <= 0 || captureHeight <= 0) return

        runCatching { imageReader?.close() }
        imageReader = null

        val reader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            if (usePrivateFormat) ImageFormat.PRIVATE else PixelFormat.RGBA_8888,
            MAX_BUFFERED_IMAGES
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ onImageAvailable(it) }, handler)

        virtualDisplay = projection?.createVirtualDisplay(
            "lumen-screen",
            captureWidth,
            captureHeight,
            captureDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        captureStartedAt = SystemClock.elapsedRealtime()
        everReceivedFrame = false
        HostSession.onScreenSize(captureWidth, captureHeight)
        if (virtualDisplay == null) {
            Log.e(TAG, "Не удалось создать VirtualDisplay")
        }
    }

    private fun recreateDisplay() {
        if (releasing) return
        if (h264Mode) return
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        createDisplay()
    }

    /**
     * Переключает захват на аппаратный H.264: VirtualDisplay рисует в Surface кодека.
     * Нужно для EMUI/HarmonyOS, где ImageReader не отдаёт кадры.
     */
    private fun switchToH264() {
        if (h264Mode || releasing || h264Unsupported) return
        val handler = captureHandler ?: return
        val fps = HostSession.requestedFps.takeIf { it > 0 } ?: Prefs.get(this).frameRate

        // Кодек может не тянуть нативный размер (лимиты памяти/уровня H.264),
        // а проверка возможностей у некоторых прошивок врёт — поэтому пробуем
        // реальным запуском: кодек + дисплей + ожидание первого пакета.
        for (step in VIDEO_SCALES) {
            if (releasing || !HostSession.isStreaming()) return
            val videoWidth = ((captureWidth * step).toInt() / 2) * 2
            val videoHeight = ((captureHeight * step).toInt() / 2) * 2
            if (videoWidth < MIN_VIDEO_SIDE || videoHeight < MIN_VIDEO_SIDE) continue

            val encoder = H264Encoder(videoWidth, videoHeight, fps) { kind, data ->
                if (HostSession.isStreaming()) HostSession.sendVideo(kind, data)
            }
            if (!encoder.start()) continue

            val videoDensity = (captureDensity.toLong() * videoWidth / captureWidth)
                .toInt()
                .coerceAtLeast(MIN_VIDEO_DENSITY)
            runCatching { virtualDisplay?.release() }
            virtualDisplay = projection?.createVirtualDisplay(
                "lumen-screen",
                videoWidth,
                videoHeight,
                videoDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoder.inputSurface,
                null,
                handler
            )

            val deadline = SystemClock.elapsedRealtime() + VIDEO_PROBE_MS
            while (SystemClock.elapsedRealtime() < deadline &&
                encoder.packets == 0L &&
                !encoder.failed
            ) {
                runCatching { Thread.sleep(PROBE_POLL_MS) }
            }

            if (encoder.packets > 0L) {
                runCatching { imageReader?.close() }
                imageReader = null
                h264Encoder = encoder
                h264Mode = true
                lastSentAt = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "Захват переключён на H.264 ${videoWidth}x$videoHeight @ $videoDensity dpi"
                )
                encoder.requestKeyFrame()
                return
            }

            Log.w(TAG, "H.264 ${videoWidth}x$videoHeight не дал кадров — пробую меньше")
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            encoder.stop()
        }

        h264Unsupported = true
        Log.w(TAG, "H.264 не дал кадров ни в одном размере — остаёмся на JPEG")
    }

    /** Кадр из ImageFormat.PRIVATE (HardwareBuffer) — запасной путь для EMUI/HarmonyOS. */
    private fun deliverHardwareFrame(image: android.media.Image, now: Long) {
        if (!HostSession.isStreaming()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val prefs = Prefs.get(this)
        val fps = HostSession.requestedFps.takeIf { it > 0 } ?: prefs.frameRate
        val quality = HostSession.requestedQuality.takeIf { it > 0 } ?: prefs.quality
        val interval = 1000L / fps.coerceIn(Proto.MIN_FPS, Proto.MAX_FPS)
        if (now - lastSentAt < interval) return

        val hardware = image.hardwareBuffer ?: return
        val wrapped = runCatching { Bitmap.wrapHardwareBuffer(hardware, null) }.getOrNull() ?: return
        val software = runCatching { wrapped.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            ?: return
        try {
            val (jpegWidth, jpegHeight) = jpegSize(software.width, software.height)
            val scaled = if (jpegWidth == software.width && jpegHeight == software.height) {
                software
            } else {
                var frame = frameBitmap
                if (frame == null || frame.width != jpegWidth || frame.height != jpegHeight) {
                    frame?.recycle()
                    frame = Bitmap.createBitmap(jpegWidth, jpegHeight, Bitmap.Config.ARGB_8888)
                    frameBitmap = frame
                }
                frameCanvas.setBitmap(frame)
                frameCanvas.drawBitmap(
                    software,
                    Rect(0, 0, software.width, software.height),
                    Rect(0, 0, jpegWidth, jpegHeight),
                    null
                )
                frameCanvas.setBitmap(null)
                frame
            }

            jpegBuffer.reset()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 90), jpegBuffer)) {
                return
            }
            lastSentAt = now
            val jpeg = jpegBuffer.toByteArray()
            lastJpeg = jpeg
            if (!firstFrameLogged) {
                firstFrameLogged = true
                Log.i(TAG, "Первый кадр (HardwareBuffer) ${jpegWidth}x$jpegHeight, ${jpeg.size} байт")
            }
            HostSession.onFrameEncoded()
            HostSession.sendFrame(jpeg)
        } catch (throwable: Throwable) {
            Log.w(TAG, "Кадр HardwareBuffer пропущен: ${throwable.message}")
        } finally {
            runCatching { software.recycle() }
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            everReceivedFrame = true
            if (usePrivateFormat) {
                deliverHardwareFrame(image, now)
                return
            }
            if (!HostSession.isStreaming()) return

            val prefs = Prefs.get(this)
            val fps = HostSession.requestedFps.takeIf { it > 0 } ?: prefs.frameRate
            val quality = HostSession.requestedQuality.takeIf { it > 0 } ?: prefs.quality
            val interval = 1000L / fps.coerceIn(Proto.MIN_FPS, Proto.MAX_FPS)
            if (now - lastSentAt < interval) return

            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride <= 0 || rowStride <= 0) return

            val crop = image.cropRect
            val width = crop.width()
            val height = crop.height()
            if (width <= 0 || height <= 0) return

            val paddedWidth = (rowStride + pixelStride - 1) / pixelStride
            var padded = paddedBitmap
            if (padded == null || padded.width != paddedWidth || padded.height != image.height) {
                padded?.recycle()
                padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                paddedBitmap = padded
            }
            padded.copyPixelsFromBuffer(plane.buffer)

            val target: Bitmap
            val (jpegWidth, jpegHeight) = jpegSize(width, height)
            val exactCrop = crop.left == 0 && crop.top == 0 && paddedWidth == width &&
                jpegWidth == width && jpegHeight == height
            if (exactCrop) {
                target = padded
            } else {
                var frame = frameBitmap
                if (frame == null || frame.width != jpegWidth || frame.height != jpegHeight) {
                    frame?.recycle()
                    frame = Bitmap.createBitmap(jpegWidth, jpegHeight, Bitmap.Config.ARGB_8888)
                    frameBitmap = frame
                }
                frameCanvas.setBitmap(frame)
                frameCanvas.drawBitmap(padded, crop, Rect(0, 0, jpegWidth, jpegHeight), null)
                frameCanvas.setBitmap(null)
                target = frame
            }

            jpegBuffer.reset()
            if (!target.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 90), jpegBuffer)) {
                return
            }
            lastSentAt = now
            val jpeg = jpegBuffer.toByteArray()
            lastJpeg = jpeg
            HostSession.onFrameEncoded()
            if (!firstFrameLogged) {
                firstFrameLogged = true
                Log.i(
                    TAG,
                    "Первый кадр ${jpegWidth}x$jpegHeight (экран ${width}x$height), " +
                        "${jpegBuffer.size()} байт"
                )
            }
            HostSession.sendFrame(jpegBuffer.toByteArray())        } catch (throwable: Throwable) {
            Log.w(TAG, "Кадр пропущен: ${throwable.message}")
        } finally {
            runCatching { image.close() }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val manager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lumen:capture").apply {
            setReferenceCounted(false)
            runCatching { acquire(MAX_WAKE_LOCK_MS) }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
    }

    /**
     * Показывает плавающую кнопку «Остановить» на экране телефона, как только
     * к нему подключается партнёр, и убирает её после отключения.
     */
    private fun watchOverlay() {
        if (overlayWatching) return
        overlayWatching = true
        if (stopOverlay == null) stopOverlay = StreamOverlay(this) { stopCapture() }
        overlayScope.launch {
            HostSession.state.collect { state ->
                val overlay = stopOverlay ?: return@collect
                if (state.phase == HostSession.Phase.CONNECTED) overlay.show() else overlay.hide()
            }
        }
    }

    private fun stopCapture() {
        if (releasing) return
        releasing = true
        stopOverlay?.hide()
        captureHandler?.removeCallbacksAndMessages(null)
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
        paddedBitmap?.recycle()
        paddedBitmap = null
        frameBitmap?.recycle()
        frameBitmap = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        releaseWakeLock()
        h264Encoder?.stop()
        h264Encoder = null
        h264Mode = false
        HostSession.onCaptureState(false)
        HostSession.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        overlayScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.lumen.remote.action.START_CAPTURE"
        const val ACTION_STOP = "com.lumen.remote.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val TAG = "LumenCapture"
        private const val NOTIFICATION_ID = 42
        private const val MAX_BUFFERED_IMAGES = 3
        private const val JPEG_MAX_DIMENSION = 1280f
        private const val FIRST_FRAME_TIMEOUT_MS = 5_000L
        private const val STALL_CHECK_INTERVAL_MS = 1_500L
        private const val MAX_RECREATE_ATTEMPTS = 2

        /** Минимальная плотность виртуального дисплея при масштабировании H.264. */
        private const val MIN_VIDEO_DENSITY = 120

        /** Минимальная сторона кадра, которую имеет смысл пробовать. */
        private const val MIN_VIDEO_SIDE = 240

        /** Сколько ждём первый пакет кодека на каждом размере. */
        private const val VIDEO_PROBE_MS = 900L
        private const val PROBE_POLL_MS = 50L

        /** Доли нативного размера, которые пробуем от большего к меньшему. */
        private val VIDEO_SCALES = doubleArrayOf(1.0, 0.75, 0.5, 0.35)
        private const val FRAME_KEEPALIVE_MS = 2_000L
        private const val MAX_WAKE_LOCK_MS = 4 * 60 * 60 * 1000L

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenShareService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stop(context: Context) {
            // stopService разрешён из любого состояния и гарантированно вызывает onDestroy → stopCapture().
            runCatching { context.stopService(Intent(context, ScreenShareService::class.java)) }
            // На случай, если сервис ещё жив, дополнительно просим корректно остановиться.
            runCatching {
                context.startService(
                    Intent(context, ScreenShareService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
