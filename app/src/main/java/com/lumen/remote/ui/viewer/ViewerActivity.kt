package com.lumen.remote.ui.viewer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lumen.remote.R
import com.lumen.remote.core.BuildFlags
import com.lumen.remote.core.DeviceRecord
import com.lumen.remote.core.Prefs
import com.lumen.remote.databinding.ActivityViewerBinding
import com.lumen.remote.net.ChannelEvent
import com.lumen.remote.net.Incoming
import com.lumen.remote.net.LanClient
import com.lumen.remote.net.NetUtils
import com.lumen.remote.net.PeerChannel
import com.lumen.remote.net.Proto
import com.lumen.remote.net.RelayChannel
import com.lumen.remote.ui.common.InputViews
import com.lumen.remote.util.GallerySaver
import com.lumen.remote.util.dp
import com.lumen.remote.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var prefs: Prefs

    private var mode: String = Prefs.MODE_LAN
    private var address: String = ""
    private var pin: String = ""
    private var myName: String = ""

    private var channel: PeerChannel? = null
    private var sessionJob: Job? = null
    private var pingJob: Job? = null

    @Volatile
    private var connected = false
    @Volatile
    private var peerControlAvailable = true
    private var controlEnabled = true
    private var peerIsIos = false
    private var iosNoticeShown = false
    private var controlHintShown = false
    private var h264Decoder: H264Decoder? = null

    /** Свой hello отправлен в текущем сеансе (не дублируем при повторном hello). */
    private var helloSent = false

    private var videoWidth = 0
    private var videoHeight = 0
    private var hostEncoded = 0L
    private var hostSent = 0L
    private var hostCaptureActive = false
    private val noFramesHint = Runnable {
        if (connected && currentBitmap == null) {
            val message = when {
                !hostCaptureActive -> getString(R.string.viewer_no_frames_inactive)
                hostEncoded == 0L -> getString(R.string.viewer_no_frames_capture)
                else -> getString(R.string.viewer_no_frames_network, hostEncoded)
            }
            showStatus(message, showProgress = false, showRetry = false)
        }
    }

    private var currentBitmap: Bitmap? = null
    private var peerName = ""
    private var peerPlatform = "Android"
    private var peerModel = ""

    private var latencyMs = 0
    private var framesInWindow = 0
    private var fps = 0
    private var windowStartedAt = 0L
    private var sessionStartedAt = 0L

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            saveSnapshot()
        } else {
            toast(getString(R.string.viewer_screenshot_permission))
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            updateSessionInfo()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs.get(this)

        mode = intent.getStringExtra(EXTRA_MODE) ?: Prefs.MODE_LAN
        address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
        myName = intent.getStringExtra(EXTRA_NAME) ?: prefs.deviceName

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8))
            binding.bottomBar.updatePadding(bottom = bars.bottom + dp(10))
            insets
        }

        binding.btnMinimize.setOnClickListener { finish() }
        binding.btnEnd.setOnClickListener {
            disconnect()
            finish()
        }
        binding.btnKeyboard.setOnClickListener { showKeyboardDialog() }
        binding.btnRemoteBack.setOnClickListener { sendKey(Proto.KEY_BACK) }
        binding.btnRemoteHome.setOnClickListener { sendKey(Proto.KEY_HOME) }
        binding.btnRemoteRecents.setOnClickListener { sendKey(Proto.KEY_RECENTS) }
        binding.btnSnapshot.setOnClickListener { takeSnapshot() }
        binding.btnMore.setOnClickListener { showActionsSheet() }
        binding.btnRetry.setOnClickListener { connect() }
        binding.btnControlChip.setOnClickListener { toggleControl() }

        binding.tvPeerName.text = address
        binding.tvPeerInfo.text = getString(
            if (mode == Prefs.MODE_LAN) R.string.viewer_lan_mode else R.string.viewer_relay_mode
        )
        binding.tvQuality.text = getString(R.string.viewer_latency, 0)

        setupTouch()
        setupVideoSurface()
        updateControlChip()
        connect()
    }

    override fun onStart() {
        super.onStart()
        mainHandler.post(ticker)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        disconnect()
        h264Decoder?.release()
        h264Decoder = null
        currentBitmap?.recycle()
        currentBitmap = null
        super.onDestroy()
    }

    // region Соединение

    private fun connect() {
        sessionJob?.cancel()
        pingJob?.cancel()
        channel?.close(null)
        channel = null
        connected = false
        peerControlAvailable = true

        if (mode == Prefs.MODE_RELAY && !BuildFlags.isRelayUrlAllowed(prefs.relayUrl)) {
            showStatus(
                getString(
                    if (prefs.relayUrl.isBlank()) R.string.settings_relay_required
                    else R.string.relay_wss_required
                ),
                showProgress = false,
                showRetry = false
            )
            return
        }

        showStatus(getString(R.string.viewer_connecting), showProgress = true, showRetry = false)

        sessionJob = lifecycleScope.launch {
            val newChannel = try {
                withContext(Dispatchers.IO) {
                    if (mode == Prefs.MODE_LAN) {
                        LanClient.connect(address, NetUtils.DEFAULT_PORT)
                    } else {
                        RelayChannel.connect(
                            url = prefs.relayUrl,
                            role = RelayChannel.ROLE_VIEWER,
                            code = address,
                            pin = pin
                        )
                    }
                }
            } catch (e: Exception) {
                showStatus(mapError(e), showProgress = false, showRetry = true)
                return@launch
            }
            channel = newChannel
            helloSent = false
            newChannel.send(Proto.auth(pin))

            launch {
                newChannel.events.collect { event ->
                    when (event) {
                        is ChannelEvent.Closed -> onSessionLost(event.reason)
                        is ChannelEvent.Failed -> onSessionLost(event.message)
                        ChannelEvent.Open -> Unit
                    }
                }
            }

            for (message in newChannel.incoming) {
                when (message) {
                    is Incoming.Text -> handleJson(message.json)
                    is Incoming.Frame -> handleFrame(message.jpeg)
                    is Incoming.Video -> handleVideoPacket(message.packet)
                }
            }
        }
    }

    private fun handleJson(json: JSONObject) {
        when (Proto.type(json)) {
            Proto.AUTH_OK -> {
                applyPeerInfo(Proto.parseHello(json))
                sendHello()
            }
            Proto.AUTH_FAIL -> {
                showStatus(getString(R.string.error_wrong_pin), showProgress = false, showRetry = true)
                channel?.close("auth_failed")
            }
            Proto.HELLO -> {
                onPeerReady(json)
                // Старые хосты (1.0.5 и раньше) отвечают на auth сразу hello,
                // без auth_ok — без нашего hello они остаются «в ожидании».
                sendHello()
            }
            Proto.PONG -> {
                latencyMs = (System.currentTimeMillis() - Proto.parseTimestamp(json))
                    .toInt()
                    .coerceIn(0, MAX_LATENCY_MS)
                updateQualityPill()
            }
            Proto.PING -> channel?.send(Proto.pong(Proto.parseTimestamp(json)))
            Proto.STATUS -> {
                val status = Proto.parseStatus(json)
                hostEncoded = status.framesEncoded
                hostSent = status.framesSent
                hostCaptureActive = status.captureActive
            }
            Proto.BYE -> onSessionLost(getString(R.string.host_disconnected))
        }
    }

    /** Отправляет свой hello (с поддержкой кодеков) один раз за сеанс. */
    private fun sendHello() {
        if (helloSent) return
        val ch = channel ?: return
        helloSent = true
        ch.send(
            Proto.hello(
                name = myName,
                platform = "Android",
                model = Build.MODEL ?: "Android",
                width = 0,
                height = 0,
                control = false,
                pinRequired = false,
                codecs = "${Proto.CODEC_H264},${Proto.CODEC_JPEG}"
            )
        )
    }

    /** Обновляет данные партнёра из hello-сообщения. */
    private fun applyPeerInfo(hello: Proto.Hello) {
        peerName = hello.name
        peerPlatform = hello.platform
        peerModel = hello.model
        peerControlAvailable = hello.control
        peerIsIos = hello.platform.contains("ios", ignoreCase = true)
        if (peerIsIos) controlEnabled = false
        binding.tvPeerName.text = peerName
        binding.tvPeerInfo.text = if (hello.control) {
            getString(R.string.viewer_peer_info, peerPlatform)
        } else {
            getString(R.string.viewer_no_control_info, peerPlatform)
        }
        applyPlatformUi()
        ensureDecoder(hello.width, hello.height)
        updateControlChip()
    }

    /** На iOS недоступна инъекция жестов — оставляем только действия-команды. */
    private fun applyPlatformUi() {
        val gesturesAvailable = !peerIsIos
        binding.btnKeyboard.isVisible = gesturesAvailable
        binding.btnRemoteBack.isVisible = gesturesAvailable
        binding.btnRemoteHome.isVisible = gesturesAvailable
        binding.btnRemoteRecents.isVisible = gesturesAvailable
    }

    private fun onPeerReady(json: JSONObject) {
        applyPeerInfo(Proto.parseHello(json))
        connected = true
        sessionStartedAt = System.currentTimeMillis()

        hideStatus()
        startPingLoop()
        requestQuality()
        saveDevice()
        mainHandler.removeCallbacks(noFramesHint)
        mainHandler.postDelayed(noFramesHint, NO_FRAMES_TIMEOUT_MS)
        if (peerIsIos) {
            if (!iosNoticeShown) {
                toast(getString(R.string.viewer_ios_connected))
                iosNoticeShown = true
            }
        } else if (!peerControlAvailable) {
            toast(getString(R.string.viewer_control_hint))
            controlEnabled = false
            updateControlChip()
        }
    }

    private suspend fun handleFrame(jpeg: ByteArray) {
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }.getOrNull()
        } ?: return
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) {
                bitmap.recycle()
                return@withContext
            }
            val previous = currentBitmap
            currentBitmap = bitmap
            binding.remoteView.setImageBitmap(bitmap)
            // Старый кадр освобождаем после отрисовки, чтобы исключить гонку с GPU-отрисовкой.
            previous?.let { old ->
                binding.remoteView.post { runCatching { old.recycle() } }
            }
            if (!connected) {
                connected = true
                sessionStartedAt = System.currentTimeMillis()
                hideStatus()
            }
            hideStatus()
            mainHandler.removeCallbacks(noFramesHint)
            framesInWindow++
            val now = SystemClock.elapsedRealtime()
            if (windowStartedAt == 0L) windowStartedAt = now
            if (now - windowStartedAt >= 1000) {
                fps = (framesInWindow * 1000 / max(1L, now - windowStartedAt)).toInt()
                framesInWindow = 0
                windowStartedAt = now
                updateQualityPill()
            }
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = lifecycleScope.launch {
            while (isActive) {
                channel?.send(Proto.ping())
                delay(2000)
            }
        }
    }

    private fun requestQuality() {
        channel?.send(Proto.quality(prefs.frameRate, prefs.quality))
    }

    private fun saveDevice() {
        prefs.upsertDevice(
            DeviceRecord(
                id = address,
                name = peerName.ifBlank { address },
                platform = peerPlatform,
                mode = mode,
                address = address
            )
        )
    }

    private fun onSessionLost(reason: String?) {
        if (isFinishing) return
        val message = when {
            reason.isNullOrBlank() -> getString(R.string.host_disconnected)
            reason.contains("auth", ignoreCase = true) -> getString(R.string.error_wrong_pin)
            else -> reason
        }
        showStatus(message, showProgress = false, showRetry = true)
    }

    private fun disconnect() {
        pingJob?.cancel()
        pingJob = null
        sessionJob?.cancel()
        sessionJob = null
        val current = channel
        channel = null
        current?.let {
            runCatching { it.send(Proto.bye()) }
            it.close(getString(R.string.viewer_disconnect))
        }
        connected = false
    }

    private fun mapError(e: Exception): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("wrong_pin") -> getString(R.string.error_wrong_pin)
            raw.contains("not_found", true) || raw.contains("unreachable", true) ->
                getString(R.string.error_host_unreachable)
            raw.contains("busy", true) -> getString(R.string.error_busy)
            raw.contains("relay", true) || raw.contains("ws", true) ->
                getString(R.string.error_relay)
            raw.isBlank() -> getString(R.string.error_unknown)
            else -> getString(R.string.error_host_unreachable)
        }
    }

    // endregion

    // region Управление

    private fun setupTouch() {
        binding.remoteView.setOnTouchListener { view, event ->
            if (!controlEnabled || !peerControlAvailable || peerIsIos) {
                if (!peerIsIos && !peerControlAvailable && !controlHintShown) {
                    controlHintShown = true
                    toast(getString(R.string.viewer_tap_disabled))
                }
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = SystemClock.elapsedRealtime()
                    moved = false
                    showDot(event.x, event.y)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > dp(14) || abs(event.y - downY) > dp(14)) moved = true
                    showDot(event.x, event.y)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    binding.touchDot.visibility = View.GONE
                    val point = binding.remoteView.normalizedPoint(event.x, event.y)
                    val start = binding.remoteView.normalizedPoint(downX, downY)
                    val duration = (SystemClock.elapsedRealtime() - downAt).toInt()
                    if (point != null && start != null) {
                        if (!moved && duration < 320) {
                            channel?.send(Proto.tap(point.x, point.y))
                        } else {
                            channel?.send(
                                Proto.swipe(
                                    start.x,
                                    start.y,
                                    point.x,
                                    point.y,
                                    duration.coerceIn(80, 900)
                                )
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    binding.touchDot.visibility = View.GONE
                    true
                }
                else -> false
            }
        }
    }

    // region H.264

    private fun setupVideoSurface() {
        binding.videoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                h264Decoder?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                h264Decoder?.setSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                h264Decoder?.setSurface(null)
            }
        })
        binding.videoView.setOnTouchListener { _, event -> handleVideoTouch(event) }
    }

    private fun ensureDecoder(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || h264Decoder != null) return
        val decoder = H264Decoder(width, height) { videoW, videoH ->
            videoWidth = videoW
            videoHeight = videoH
            applyVideoLayout()
        }
        h264Decoder = decoder
        binding.videoView.holder.surface?.let { decoder.setSurface(it) }
    }

    private fun handleVideoPacket(packet: ByteArray) {
        if (packet.isEmpty()) return
        val decoder = h264Decoder ?: return
        val kind = packet[0].toInt()
        val data = packet.copyOfRange(1, packet.size)

        if (!connected) {
            connected = true
            sessionStartedAt = System.currentTimeMillis()
        }
        mainHandler.removeCallbacks(noFramesHint)
        hideStatus()

        if (binding.remoteView.visibility != View.GONE) {
            binding.remoteView.visibility = View.GONE
            binding.videoView.visibility = View.VISIBLE
            applyVideoLayout()
        }

        if (kind == Proto.VIDEO_KIND_CONFIG) decoder.onConfig(data) else decoder.onFrame(data)
    }

    /** Вписывает видео в экран с сохранением пропорций (как letterbox у RemoteScreenView). */
    private fun applyVideoLayout() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val containerWidth = binding.root.width - dp(12)
        val containerHeight = binding.root.height - dp(12)
        if (containerWidth <= 0 || containerHeight <= 0) return
        val scale = min(
            containerWidth.toFloat() / videoWidth,
            containerHeight.toFloat() / videoHeight
        )
        val width = (videoWidth * scale).toInt().coerceAtLeast(1)
        val height = (videoHeight * scale).toInt().coerceAtLeast(1)
        val params = binding.videoView.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            binding.videoView.layoutParams = params
        }
    }

    private fun handleVideoTouch(event: MotionEvent): Boolean {
        if (!controlEnabled || !peerControlAvailable || peerIsIos) {
            if (!peerIsIos && !peerControlAvailable && !controlHintShown) {
                controlHintShown = true
                toast(getString(R.string.viewer_tap_disabled))
            }
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = SystemClock.elapsedRealtime()
                moved = false
                showDot(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > dp(14) || abs(event.y - downY) > dp(14)) moved = true
                showDot(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                binding.touchDot.visibility = View.GONE
                val start = videoPoint(downX, downY)
                val end = videoPoint(event.x, event.y)
                val duration = (SystemClock.elapsedRealtime() - downAt).toInt()
                if (start != null && end != null) {
                    if (!moved && duration < 320) {
                        channel?.send(Proto.tap(end.x, end.y))
                    } else {
                        channel?.send(
                            Proto.swipe(start.x, start.y, end.x, end.y, duration.coerceIn(80, 900))
                        )
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                binding.touchDot.visibility = View.GONE
                return true
            }
            else -> return false
        }
    }

    private fun videoPoint(x: Float, y: Float): PointF? {
        val width = binding.videoView.width.toFloat()
        val height = binding.videoView.height.toFloat()
        if (width <= 0f || height <= 0f) return null
        val nx = x / width
        val ny = y / height
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        return PointF(nx, ny)
    }

    // endregion

    private fun showDot(x: Float, y: Float) {
        val dot = binding.touchDot
        dot.visibility = View.VISIBLE
        dot.translationX = x - dot.width / 2f
        dot.translationY = y - dot.height / 2f
    }

    private fun toggleControl() {
        if (peerIsIos) {
            // iOS не принимает жесты — предлагаем доступные действия.
            showActionsSheet()
            return
        }
        if (!peerControlAvailable) {
            toast(getString(R.string.viewer_control_hint))
            return
        }
        controlEnabled = !controlEnabled
        updateControlChip()
    }

    private fun updateControlChip() {
        val label = when {
            peerIsIos -> R.string.viewer_ios_chip
            controlEnabled -> R.string.viewer_control
            else -> R.string.viewer_view_only
        }
        binding.btnControlChip.text = getString(label)
        val active = !peerIsIos && controlEnabled
        binding.btnControlChip.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.text_primary else R.color.text_secondary)
        )
        binding.btnControlChip.alpha = if (peerControlAvailable || peerIsIos) 1f else 0.55f
    }

    private fun sendKey(key: String) {
        if (peerIsIos) {
            toast(getString(R.string.viewer_ios_gesture_denied))
            return
        }
        if (!peerControlAvailable) {
            toast(getString(R.string.viewer_control_hint))
            return
        }
        channel?.send(Proto.key(key))
    }

    // region Действия (работают и на iOS)

    private fun showActionsSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_actions, null)
        view.findViewById<View>(R.id.tvIosNote).isVisible = peerIsIos
        view.findViewById<View>(R.id.rowOpenUrl).setOnClickListener {
            sheet.dismiss()
            showOpenUrlDialog()
        }
        view.findViewById<View>(R.id.rowClipboard).setOnClickListener {
            sheet.dismiss()
            showClipboardDialog()
        }
        view.findViewById<View>(R.id.rowQuality).setOnClickListener {
            sheet.dismiss()
            showQualityDialog()
        }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun showOpenUrlDialog() {
        val field = InputViews.field(
            context = this,
            hint = getString(R.string.viewer_url_hint),
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.viewer_open_url_title)
            .setView(field.container)
            .setPositiveButton(R.string.viewer_send) { _, _ ->
                val url = field.input.text?.toString()?.trim().orEmpty()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    toast(getString(R.string.viewer_url_invalid))
                    return@setPositiveButton
                }
                sendCommand(Proto.ACTION_OPEN_URL, url)
                toast(getString(R.string.viewer_url_sent))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showClipboardDialog() {
        val field = InputViews.field(
            context = this,
            hint = getString(R.string.viewer_text_hint)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.viewer_clipboard_title)
            .setView(field.container)
            .setPositiveButton(R.string.viewer_send) { _, _ ->
                val text = field.input.text?.toString().orEmpty()
                if (text.isBlank()) return@setPositiveButton
                sendCommand(Proto.ACTION_CLIPBOARD, text)
                toast(getString(R.string.viewer_clipboard_sent))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showQualityDialog() {
        val options = arrayOf(
            getString(R.string.viewer_quality_smooth_item),
            getString(R.string.viewer_quality_sharp_item)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.viewer_quality)
            .setItems(options) { _, which ->
                val fps = if (which == 0) SMOOTH_FPS else SHARP_FPS
                val quality = if (which == 0) SMOOTH_QUALITY else SHARP_QUALITY
                channel?.send(Proto.quality(fps, quality))
                toast(getString(R.string.viewer_quality_applied))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sendCommand(action: String, value: String) {
        if (!peerControlAvailable && !peerIsIos) {
            toast(getString(R.string.viewer_control_hint))
            return
        }
        channel?.send(Proto.cmd(action, value))
    }

    // endregion

    private fun showKeyboardDialog() {
        val field = InputViews.field(
            context = this,
            hint = getString(R.string.viewer_keyboard_hint)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.viewer_keyboard)
            .setView(field.container)
            .setPositiveButton(R.string.viewer_send) { _, _ ->
                val text = field.input.text?.toString().orEmpty()
                if (text.isNotEmpty() && peerControlAvailable) channel?.send(Proto.text(text))
            }
            .setNeutralButton(R.string.viewer_back) { _, _ -> sendKey(Proto.KEY_BACK) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun takeSnapshot() {
        if (currentBitmap == null) {
            toast(getString(R.string.viewer_waiting_frames))
            return
        }
        if (needsLegacyStoragePermission()) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        saveSnapshot()
    }

    /** На Android 9 и ниже запись в галерею требует разрешения на память. */
    private fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    private fun saveSnapshot() {
        val bitmap = currentBitmap ?: return
        val saved = GallerySaver.save(this, bitmap)
        toast(getString(if (saved) R.string.viewer_screenshot_saved else R.string.error_unknown))
    }

    // endregion

    // region UI

    private fun showStatus(message: String, showProgress: Boolean, showRetry: Boolean) {
        binding.statusPanel.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.pbConnect.visibility = if (showProgress) View.VISIBLE else View.GONE
        binding.btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
        binding.tvStatusHint.visibility = View.GONE
    }

    private fun hideStatus() {
        binding.statusPanel.visibility = View.GONE
    }
    private fun updateQualityPill() {
        val text = getString(R.string.viewer_latency, latencyMs)
        binding.tvQuality.text = if (fps > 0) "$text · $fps" else text
    }

    private fun updateSessionInfo() {
        if (!connected) {
            binding.tvSessionInfo.text = ""
            return
        }
        val seconds = (System.currentTimeMillis() - sessionStartedAt) / 1000
        val time = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
        binding.tvSessionInfo.text = getString(R.string.viewer_session_info, time, fps)
    }

    // endregion

    companion object {
        private const val NO_FRAMES_TIMEOUT_MS = 5_000L
        private const val MAX_LATENCY_MS = 9999
        private const val SMOOTH_FPS = 8
        private const val SMOOTH_QUALITY = 45
        private const val SHARP_FPS = 16
        private const val SHARP_QUALITY = 75
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_PIN = "pin"
        private const val EXTRA_NAME = "name"

        fun intent(
            context: Context,
            mode: String,
            address: String,
            pin: String,
            viewerName: String
        ): Intent = Intent(context, ViewerActivity::class.java)
            .putExtra(EXTRA_MODE, mode)
            .putExtra(EXTRA_ADDRESS, address)
            .putExtra(EXTRA_PIN, pin)
            .putExtra(EXTRA_NAME, viewerName)
    }
}
