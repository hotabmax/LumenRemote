package com.lumen.remote.host

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.lumen.remote.R
import com.lumen.remote.control.RemoteControlService
import com.lumen.remote.core.Prefs
import com.lumen.remote.net.ChannelEvent
import com.lumen.remote.net.Discovery
import com.lumen.remote.net.Incoming
import com.lumen.remote.net.LanServer
import com.lumen.remote.net.NetUtils
import com.lumen.remote.net.PeerChannel
import com.lumen.remote.net.Proto
import com.lumen.remote.net.RelayChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Состояние сеанса «принимаю подключение»: код, PIN, транспорт и активный канал.
 */
object HostSession {

    enum class Mode(val prefValue: String) {
        LAN(Prefs.MODE_LAN),
        RELAY(Prefs.MODE_RELAY);
    }

    enum class Phase { IDLE, WAITING, PENDING, CONNECTED, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val mode: Mode = Mode.LAN,
        val code: String = "",
        val pin: String = "",
        val ip: String? = null,
        val relayUrl: String? = null,
        val viewerName: String? = null,
        val viewerPlatform: String? = null,
        val viewOnly: Boolean = false,
        val framesSent: Long = 0,
        val fps: Int = 0,
        val lastFrameAt: Long = 0,
        val contentVisible: Boolean = true,
        val framesEncoded: Long = 0,
        val captureActive: Boolean = false,
        val lastError: String? = null,
        val screenWidth: Int = 0,
        val screenHeight: Int = 0,
        val startedAt: Long = 0
    ) {
        val isActive: Boolean get() = phase == Phase.WAITING || phase == Phase.PENDING ||
            phase == Phase.CONNECTED
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val LOCAL_KEY = "local"
    private const val FAILURE_PAUSE_MS = 800L
    private const val APPROVAL_TIMEOUT_MS = 60_000L
    private var appContext: Context? = null
    private var prefs: Prefs? = null
    private var lanServer: LanServer? = null
    private var listenerJob: Job? = null
    private var relayJob: Job? = null
    private var relayReconnectJob: Job? = null

    @Volatile
    private var channel: PeerChannel? = null
    @Volatile
    private var authenticated = false

    /** Ждём подтверждения подключения от владельца телефона. */
    private var awaitingApproval = false
    private var pendingToken: String? = null
    private var approvalJob: Job? = null
    @Volatile
    private var stopping = false

    /** Требования по качеству от партнёра. */
    @Volatile
    var requestedFps: Int = 0
        private set

    /** Партнёр умеет принимать H.264 (Android-клиент). */
    @Volatile
    var viewerSupportsH264: Boolean = false
        private set

    /** Последний пакет конфигурации H.264 ([kind][SPS+PPS]) для повторной отправки. */
    @Volatile
    private var lastVideoConfig: ByteArray? = null

    val wantsH264: Boolean get() = viewerSupportsH264

    @Volatile
    var requestedQuality: Int = 0
        private set

    private val framesSent = AtomicLong(0)
    private val framesEncoded = AtomicLong(0)
    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0

    fun start(context: Context, mode: Mode) {
        val ctx = context.applicationContext
        stop(silent = true)
        stopping = false
        appContext = ctx
        val p = Prefs.get(ctx)
        prefs = p
        requestedFps = p.frameRate
        requestedQuality = p.quality
        authenticated = false
        framesSent.set(0)
        _state.value = State(
            phase = Phase.WAITING,
            mode = mode,
            code = p.myId,
            pin = p.myPin,
            ip = NetUtils.localIp(),
            relayUrl = p.relayUrl,
            viewOnly = false,
            startedAt = System.currentTimeMillis()
        )
        when (mode) {
            Mode.LAN -> startLan(ctx, p)
            Mode.RELAY -> startRelay()
        }
    }

    fun stop(silent: Boolean = false) {
        stopping = true
        listenerJob?.cancel()
        listenerJob = null
        relayJob?.cancel()
        relayJob = null
        relayReconnectJob?.cancel()
        relayReconnectJob = null
        channel?.close("Сеанс завершён")
        channel = null
        authenticated = false
        awaitingApproval = false
        pendingToken = null
        approvalJob?.cancel()
        approvalJob = null
        ConnectionApproval.hide(appContext)
        AuthGuard.prune()
        lanServer?.stop()
        lanServer = null
        Discovery.stopBroadcast()
        if (!silent) {
            _state.value = State()
        }
    }

    fun onScreenSize(width: Int, height: Int) {
        _state.update { it.copy(screenWidth = width, screenHeight = height) }
    }

    fun isStreaming(): Boolean = _state.value.phase == Phase.CONNECTED

    fun sendFrame(jpeg: ByteArray) {
        val ch = channel ?: return
        if (_state.value.phase != Phase.CONNECTED) return
        if (!ch.sendFrame(jpeg)) return
        trackFrame()
    }

    /** Обновляет счётчик кадров и живую частоту (общий для JPEG и H.264). */
    private fun trackFrame() {
        val total = framesSent.incrementAndGet()
        val now = System.currentTimeMillis()
        if (fpsWindowStart == 0L) {
            fpsWindowStart = now
            fpsWindowFrames = 0
        }
        fpsWindowFrames += 1
        var fps = _state.value.fps
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            fps = (fpsWindowFrames * 1000 / elapsed).toInt()
            fpsWindowStart = now
            fpsWindowFrames = 0
        }
        _state.update { it.copy(framesSent = total, fps = fps, lastFrameAt = now) }
    }

    /** Система сообщает, что транслируемый контент скрыт (экран выключен/заблокирован). */
    /** Кадр JPEG успешно закодирован сервисом захвата (даже если партнёр не подключён). */
    fun onFrameEncoded() {
        framesEncoded.incrementAndGet()
        _state.update { it.copy(framesEncoded = framesEncoded.get()) }
    }

    /** Отправляет видеопакет H.264 [kind][data]. */
    fun sendVideo(kind: Int, data: ByteArray) {
        val ch = channel ?: return
        if (_state.value.phase != Phase.CONNECTED) return
        val packet = ByteArray(data.size + 1)
        packet[0] = kind.toByte()
        data.copyInto(packet, destinationOffset = 1)
        if (!ch.sendVideoFrame(packet)) return
        if (kind == Proto.VIDEO_KIND_CONFIG) lastVideoConfig = packet.copyOf()
        framesEncoded.incrementAndGet()
        _state.update { it.copy(framesEncoded = framesEncoded.get()) }
        // Пакеты конфигурации не считаем кадрами.
        if (kind != Proto.VIDEO_KIND_CONFIG) trackFrame()
    }

    /** Сервис захвата запущен/остановлен. */
    fun onCaptureState(active: Boolean) {
        _state.update { it.copy(captureActive = active) }
    }

    /** Система сообщает, что транслируемый контент скрыт (экран выключен/заблокирован). */
    fun onContentVisibilityChanged(visible: Boolean) {
        _state.update { it.copy(contentVisible = visible) }
    }

    /** Отправляет партнёру счётчики захвата для диагностики чёрного экрана. */
    fun sendStatus(ch: PeerChannel) {
        val current = _state.value
        ch.send(
            Proto.status(
                Proto.Status(
                    framesSent = current.framesSent,
                    framesEncoded = current.framesEncoded,
                    width = current.screenWidth,
                    height = current.screenHeight,
                    visible = current.contentVisible,
                    captureActive = current.captureActive
                )
            )
        )
    }

    fun refreshControlAvailability() {
        val available = RemoteControlService.instance != null
        _state.update { it.copy(viewOnly = !available) }
        val ch = channel ?: return
        runCatching { ch.send(hostHello()) }
    }

    fun sendControlRevoked(revoked: Boolean) {
        _state.update { it.copy(viewOnly = revoked) }
        runCatching { channel?.send(hostHello()) }
    }

    // region LAN

    private fun startLan(context: Context, p: Prefs) {
        // Приватность: по настройке устройство может не афишироваться в сети.
        if (p.discoverable) {
            Discovery.startBroadcast(
                context = context,
                id = p.myId,
                name = p.deviceName,
                port = NetUtils.DEFAULT_PORT,
                requiresPin = p.requirePin
            )
        }
        val server = LanServer(
            port = NetUtils.DEFAULT_PORT,
            onChannel = { bindChannel(it) },
            onError = { message ->
                if (!stopping) fail(message)
            }
        )
        lanServer = server
        server.start()
    }

    // endregion

    // region Relay

    private fun startRelay() {
        val p = prefs ?: return
        val url = p.relayUrl
        if (!RelayChannel.isSupportedUrl(url)) {
            fail(appContext?.getString(R.string.settings_relay_invalid) ?: "relay")
            return
        }
        relayJob = scope.launch {
            try {
                val ch = RelayChannel.connect(
                    url = url,
                    role = RelayChannel.ROLE_HOST,
                    code = p.myId,
                    pin = p.myPin
                )
                bindChannel(ch)
            } catch (e: Exception) {
                if (!stopping) {
                    fail(e.message ?: "relay")
                    scheduleRelayReconnect()
                }
            }
        }
    }

    private fun scheduleRelayReconnect() {
        if (stopping) return
        if (_state.value.mode != Mode.RELAY) return
        relayReconnectJob?.cancel()
        relayReconnectJob = scope.launch {
            delay(2500)
            if (!stopping && _state.value.mode == Mode.RELAY) {
                startRelay()
            }
        }
    }

    // endregion

    private fun bindChannel(ch: PeerChannel) {
        channel?.let { previous ->
            if (previous !== ch) previous.close("Заменено новым подключением")
        }
        channel = ch
        authenticated = false
        if (_state.value.phase == Phase.IDLE) {
            _state.update { it.copy(phase = Phase.WAITING) }
        }
        listenerJob?.cancel()
        listenerJob = scope.launch {
            ch.events.collect { event ->
                if (channel !== ch) return@collect
                when (event) {
                    is ChannelEvent.Failed -> if (!stopping) {
                        fail(event.message ?: "connection")
                        if (_state.value.mode == Mode.RELAY) scheduleRelayReconnect()
                    }
                    is ChannelEvent.Closed -> onChannelClosed(ch)
                    ChannelEvent.Open -> runCatching { ch.send(hostHello(anonymous = true)) }
                }
            }
        }
        scope.launch {
            for (message in ch.incoming) {
                if (channel !== ch) break
                if (message is Incoming.Text) handleJson(message.json)
            }
        }
        scope.launch {
            while (channel === ch) {
                if (_state.value.phase == Phase.CONNECTED) sendStatus(ch)
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun onChannelClosed(ch: PeerChannel) {
        if (channel !== ch) return
        channel = null
        authenticated = false
        if (stopping) return
        _state.update {
            it.copy(
                phase = Phase.WAITING,
                viewerName = null,
                viewerPlatform = null
            )
        }
        when (_state.value.mode) {
            Mode.RELAY -> scheduleRelayReconnect()
            Mode.LAN -> Unit // LanServer продолжает принимать новые подключения
        }
    }

    private fun handleJson(json: JSONObject) {
        val ch = channel ?: return
        when (Proto.type(json)) {
            Proto.AUTH -> {
                val key = ch.remoteAddress ?: LOCAL_KEY
                val blockedMs = AuthGuard.blockRemainingMs(key)
                if (blockedMs > 0) {
                    val seconds = (blockedMs + 999) / 1000
                    ch.send(JSONObject().put(Proto.T, Proto.AUTH_FAIL))
                    scope.launch {
                        delay(200)
                        ch.close("Слишком много попыток. Повторите через $seconds с")
                    }
                    return
                }
                val requirePin = prefs?.requirePin ?: true
                val correct = !requirePin || pinMatches(Proto.parsePin(json), _state.value.pin)
                if (correct) {
                    AuthGuard.registerSuccess(key)
                    authenticated = true
                    // Зритель ждёт auth_ok с данными партнёра, а затем шлёт свой hello;
                    // отдельный hello — для совместимости со старыми клиентами.
                    ch.send(hostHello().put(Proto.T, Proto.AUTH_OK))
                    ch.send(hostHello())
                } else {
                    val blocked = AuthGuard.registerFailure(key)
                    ch.send(JSONObject().put(Proto.T, Proto.AUTH_FAIL))
                    val pause = if (blocked > 0) 0L else FAILURE_PAUSE_MS
                    scope.launch {
                        delay(pause)
                        ch.close("Неверный PIN-код")
                    }
                    _state.update { it.copy(lastError = appContext?.getString(R.string.error_wrong_pin)) }
                }
            }
            Proto.HELLO -> {
                if (!authenticated && (prefs?.requirePin ?: true)) return
                authenticated = true
                val hello = Proto.parseHello(json)
                viewerSupportsH264 = hello.codecs.contains(Proto.CODEC_H264, ignoreCase = true)
                if (prefs?.confirmConnections != false) {
                    requestApproval(hello)
                } else {
                    markConnected(hello)
                }
            }
            Proto.TAP -> if (canControl()) {
                val tap = Proto.parseTap(json)
                RemoteControlService.instance?.tap(tap.x, tap.y)
            }
            Proto.SWIPE -> if (canControl()) {
                val swipe = Proto.parseSwipe(json)
                RemoteControlService.instance?.swipe(
                    swipe.x1,
                    swipe.y1,
                    swipe.x2,
                    swipe.y2,
                    swipe.durationMs
                )
            }
            Proto.KEY -> if (canControl()) {
                RemoteControlService.instance?.pressKey(Proto.parseKey(json))
            }
            Proto.TEXT -> if (canControl() && prefs?.allowClipboard != false) {
                RemoteControlService.instance?.typeText(Proto.parseText(json))
            }
            Proto.QUALITY -> {
                val quality = Proto.parseQuality(json, requestedFps, requestedQuality)
                requestedFps = quality.fps
                requestedQuality = quality.quality
            }
            Proto.PING -> ch.send(Proto.pong(Proto.parseTimestamp(json)))
            Proto.BYE -> {
                ch.send(Proto.bye())
                ch.close("Партнёр завершил сеанс")
            }
            Proto.PEER_LEFT -> {
                _state.update {
                    it.copy(phase = Phase.WAITING, viewerName = null, viewerPlatform = null)
                }
            }
            Proto.CMD -> if (authenticated && !_state.value.viewOnly) {
                handleCommand(Proto.parseCmd(json))
            }
        }
    }

    /** Сравнение PIN без преждевременного выхода: не даёт утечки по времени. */
    private fun pinMatches(entered: String, expected: String): Boolean {
        val a = entered.toByteArray(Charsets.UTF_8)
        val b = expected.toByteArray(Charsets.UTF_8)
        return a.size == b.size && MessageDigest.isEqual(a, b)
    }

    /** Подтверждение получено — сеанс становится активным. */
    private fun markConnected(hello: Proto.Hello) {
        _state.update {
            it.copy(
                phase = Phase.CONNECTED,
                viewerName = hello.name,
                viewerPlatform = hello.platform,
                lastError = null
            )
        }
        val ch = channel ?: return
        ch.send(hostHello())
        // Новому зрителю сразу отдаём SPS/PPS: без них его декодер не стартует.
        lastVideoConfig?.let { config -> ch.sendVideoFrame(config.copyOf()) }
    }

    /** Спрашивает у владельца телефона разрешение на подключение. */
    private fun requestApproval(hello: Proto.Hello) {
        val token = UUID.randomUUID().toString()
        awaitingApproval = true
        pendingToken = token
        _state.update {
            it.copy(
                phase = Phase.PENDING,
                viewerName = hello.name,
                viewerPlatform = hello.platform,
                lastError = null
            )
        }
        val address = channel?.remoteAddress
        ConnectionApproval.show(appContext, token, hello.name, hello.platform, address)
        approvalJob?.cancel()
        approvalJob = scope.launch {
            delay(APPROVAL_TIMEOUT_MS)
            if (awaitingApproval && pendingToken == token) {
                denyPending("Подключение не подтверждено")
            }
        }
    }

    /** Пользователь разрешил подключение (из диалога или уведомления). */
    fun approvePending(token: String? = null) {
        if (!awaitingApproval) return
        if (token != null && pendingToken != null && token != pendingToken) return
        val hello = Proto.Hello(
            name = _state.value.viewerName ?: "Устройство",
            platform = _state.value.viewerPlatform ?: "Android",
            model = "",
            width = 0,
            height = 0,
            control = false,
            pinRequired = false
        )
        awaitingApproval = false
        pendingToken = null
        approvalJob?.cancel()
        approvalJob = null
        ConnectionApproval.hide(appContext)
        markConnected(hello)
    }

    /** Пользователь отклонил подключение или истекло время ожидания. */
    fun denyPending(reason: String? = null) {
        if (!awaitingApproval) return
        awaitingApproval = false
        pendingToken = null
        approvalJob?.cancel()
        approvalJob = null
        ConnectionApproval.hide(appContext)
        val ch = channel
        _state.update {
            it.copy(
                phase = Phase.WAITING,
                viewerName = null,
                viewerPlatform = null,
                lastError = reason
            )
        }
        scope.launch {
            runCatching { ch?.send(JSONObject().put(Proto.T, Proto.BYE)) }
            delay(150)
            runCatching { ch?.close(reason ?: "Подключение отклонено") }
        }
    }

    private fun canControl(): Boolean =
        authenticated && !_state.value.viewOnly && RemoteControlService.instance != null

    /**
     * Команды без инъекции жестов: работают и для iOS-партнёра.
     * Разрешены только безопасные действия: открытие http(s) и буфер обмена.
     */
    private fun handleCommand(command: Proto.Cmd) {
        val context = appContext ?: return
        when (command.action) {
            Proto.ACTION_OPEN_URL -> openUrl(context, command.value)
            Proto.ACTION_CLIPBOARD -> {
                if (prefs?.allowClipboard != false) setClipboard(context, command.value)
            }
        }
    }

    private fun openUrl(context: Context, rawUrl: String) {
        val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme != "http" && scheme != "https") return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun setClipboard(context: Context, text: String) {
        if (text.isBlank()) return
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { manager.setPrimaryClip(ClipData.newPlainText("Lumen Remote", text)) }
    }
    private fun hostHello(anonymous: Boolean = false): JSONObject {
        val p = prefs
        val current = _state.value
        return Proto.hello(
            name = if (anonymous) "" else p?.deviceName ?: "Android",
            platform = if (anonymous) "" else "Android",
            model = if (anonymous) "" else Build.MODEL ?: "Android",
            width = if (anonymous) 0 else current.screenWidth,
            height = if (anonymous) 0 else current.screenHeight,
            control = !anonymous && RemoteControlService.instance != null,
            pinRequired = p?.requirePin ?: true,
            codecs = if (anonymous) "" else "${Proto.CODEC_H264},${Proto.CODEC_JPEG}"
        )
    }

    private fun fail(message: String) {
        _state.update { it.copy(phase = Phase.ERROR, lastError = message) }
    }
}
