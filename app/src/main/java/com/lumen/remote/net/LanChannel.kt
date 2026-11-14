package com.lumen.remote.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Канал «по локальной сети»: прямое TCP-соединение с кадровой разметкой.
 *
 * Кадр: `[1 байт тип][4 байта длина][payload]`. Тип 1 — JSON, 2 — JPEG, 3 — видео.
 * Если рукопожатие прошло, payload каждого кадра дополнительно шифруется
 * AES-256-GCM с одноразовым nonce из счётчика направления.
 */
class LanChannel(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val sessionKey: ByteArray?,
    private val direction: Byte,
    private val peerDirection: Byte,
    private val prefetched: LanHandshake.Frame?,
    initialOutCounter: Long
) : BasePeerChannel() {

    /** Незашифрованный канал (старый партнёр или режим без PIN). */
    constructor(socket: Socket) : this(
        socket = socket,
        input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER)),
        output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER)),
        sessionKey = null,
        direction = LanCrypto.DIR_CLIENT,
        peerDirection = LanCrypto.DIR_HOST,
        prefetched = null,
        initialOutCounter = 0
    )

    override val remoteAddress: String?
        get() = runCatching { socket.inetAddress?.hostAddress }.getOrNull()

    /** Канал шифруется ключом сеанса. */
    override val isSecure: Boolean get() = sessionKey != null

    private val writeLock = Any()
    private val outbox = Channel<Outgoing>(capacity = OUTBOX_CAPACITY)

    private var readJob: Job? = null
    private var writeJob: Job? = null

    private var outCounter = initialOutCounter
    private var inCounter = 0L

    private class Outgoing(val type: Int, val payload: ByteArray)

    init {
        runCatching { socket.tcpNoDelay = true }
        markOpen()
        readJob = scope.launch { readLoop() }
        writeJob = scope.launch { writeLoop() }
    }

    private suspend fun readLoop() {
        try {
            prefetched?.let { deliverFrame(it.type, it.payload) }
            while (isOpen) {
                val type = input.readUnsignedByte()
                val length = input.readInt()
                if (length <= 0 || length > MAX_PAYLOAD) {
                    throw IOException("Некорректный размер кадра: $length")
                }
                val payload = ByteArray(length)
                input.readFully(payload)
                deliverFrame(type, payload)
            }
        } catch (e: Exception) {
            if (isOpen) close(e.message ?: "Соединение потеряно")
        }
    }

    /** Расшифровывает кадр (если канал защищён) и отдаёт наверх. */
    private suspend fun deliverFrame(type: Int, payload: ByteArray) {
        val key = sessionKey
        val plain = if (key == null) {
            payload
        } else {
            val decrypted = LanCrypto.open(
                key,
                LanCrypto.nonce(peerDirection, inCounter),
                type,
                payload
            ) ?: throw IOException("Кадр не прошёл проверку подлинности")
            inCounter += 1
            decrypted
        }
        when (type) {
            TYPE_JSON -> runCatching { JSONObject(String(plain, Charsets.UTF_8)) }
                .getOrNull()?.let { deliverText(it) }
            TYPE_FRAME -> postFrame(plain)
            TYPE_VIDEO -> postVideo(plain)
        }
    }

    private suspend fun writeLoop() {
        for (item in outbox) {
            if (!isOpen) break
            try {
                val key = sessionKey
                val payload = if (key == null) {
                    item.payload
                } else {
                    val sealed = LanCrypto.seal(
                        key,
                        LanCrypto.nonce(direction, outCounter),
                        item.type,
                        item.payload
                    )
                    outCounter += 1
                    sealed
                }
                synchronized(writeLock) {
                    output.writeByte(item.type)
                    output.writeInt(payload.size)
                    output.write(payload)
                    output.flush()
                }
            } catch (e: IOException) {
                close("Ошибка отправки: ${e.message}")
                break
            }
        }
    }

    /** Ставит сообщение в очередь записи: сетевой ввод-вывод идёт в фоне. */
    private fun enqueue(type: Int, payload: ByteArray): Boolean {
        if (!isOpen) return false
        val item = Outgoing(type, payload)
        if (outbox.trySend(item).isSuccess) return true
        // Очередь переполнена кадрами — управляющие сообщения не теряем.
        if (type == TYPE_JSON) {
            scope.launch {
                // Канал мог закрыться между trySend и отправкой.
                runCatching { outbox.send(item) }
            }
            return true
        }
        return false
    }

    override fun send(json: JSONObject): Boolean =
        enqueue(TYPE_JSON, json.toString().toByteArray())

    override fun sendFrame(jpeg: ByteArray): Boolean = enqueue(TYPE_FRAME, jpeg)

    override fun sendVideoFrame(packet: ByteArray): Boolean = enqueue(TYPE_VIDEO, packet)

    override fun close(reason: String?) {
        if (!isOpen) return
        runCatching { socket.close() }
        outbox.close()
        finish(reason)
        readJob?.cancel()
        writeJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TYPE_JSON = 1
        private const val TYPE_FRAME = 2
        private const val TYPE_VIDEO = 3
        private const val BUFFER = 64 * 1024
        private const val OUTBOX_CAPACITY = 32
        private const val MAX_PAYLOAD = 8 * 1024 * 1024
    }
}

/**
 * Принимает входящие подключения в локальной сети.
 *
 * Если задан [pinProvider], хост сначала пытается провести защищённое
 * рукопожатие; кадр старого клиента обрабатывается как обычно.
 */
class LanServer(
    private val port: Int,
    private val pinProvider: (() -> String)? = null,
    private val onChannel: (PeerChannel) -> Unit,
    private val onError: (String) -> Unit
) {

    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val activeClients = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var running = false
        private set

    fun start() {
        if (running) return
        running = true
        scope.launch {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
                serverSocket = socket
                while (running) {
                    val client = socket.accept()
                    // Ограничение одновременных подключений: без него перебор
                    // PIN и нехватка ресурсов превращаются в тривиальный DoS.
                    if (activeClients.get() >= MAX_CLIENTS) {
                        Log.w(TAG, "Отклонено подключение: занято ${activeClients.get()} слотов")
                        runCatching { client.close() }
                        continue
                    }
                    runCatching { client.tcpNoDelay = true }
                    activeClients.incrementAndGet()
                    val channel = acceptChannel(client)
                    if (channel == null) {
                        activeClients.decrementAndGet()
                        continue
                    }
                    scope.launch {
                        channel.events.collect { event ->
                            if (event is ChannelEvent.Closed || event is ChannelEvent.Failed) {
                                activeClients.decrementAndGet()
                            }
                        }
                    }
                    onChannel(channel)
                }
            } catch (e: Exception) {
                if (running) onError(e.message ?: "Не удалось открыть порт $port")
            }
        }
    }

    /** Готовит канал: с шифрованием, если партнёр его поддерживает. */
    private fun acceptChannel(client: Socket): PeerChannel? {
        val input = DataInputStream(BufferedInputStream(client.getInputStream(), BUFFER))
        val output = DataOutputStream(BufferedOutputStream(client.getOutputStream(), BUFFER))
        val provider = pinProvider ?: return LanChannel(client)
        var result: LanHandshake.HostResult? = null
        LanHandshake.withHandshakeTimeout(client) {
            result = LanHandshake.host(input, output, provider)
        }
        val handshake = result ?: return null
        if (handshake.key == null && handshake.prefetched == null) {
            runCatching { client.close() }
            return null
        }
        return LanChannel(
            socket = client,
            input = input,
            output = output,
            sessionKey = handshake.key,
            direction = LanCrypto.DIR_HOST,
            peerDirection = LanCrypto.DIR_CLIENT,
            prefetched = handshake.prefetched,
            initialOutCounter = handshake.outCounterStart
        )
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    private companion object {
        const val TAG = "LumenLanServer"
        const val BUFFER = 64 * 1024

        /** Максимум одновременных входящих соединений. */
        const val MAX_CLIENTS = 4
    }
}

object LanClient {

    /**
     * Подключается к хосту.
     *
     * При заданном [pin] сначала выполняется защищённое рукопожатие; если хост
     * старый и не поддерживает шифрование, соединение продолжается без него.
     */
    suspend fun connect(
        host: String,
        port: Int,
        pin: String? = null,
        timeoutMs: Int = 8000
    ): PeerChannel = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host.trim(), port), timeoutMs)
            socket.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER))
            var key: ByteArray? = null
            if (pin != null) {
                LanHandshake.withHandshakeTimeout(socket) {
                    key = LanHandshake.client(input, output, pin)
                }
                if (key == null) Log.w(TAG, "Хост без шифрования — соединение открытое")
            }
            LanChannel(
                socket = socket,
                input = input,
                output = output,
                sessionKey = key,
                direction = LanCrypto.DIR_CLIENT,
                peerDirection = LanCrypto.DIR_HOST,
                prefetched = null,
                initialOutCounter = 0
            )
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private const val TAG = "LumenLanClient"
    private const val BUFFER = 64 * 1024
}
