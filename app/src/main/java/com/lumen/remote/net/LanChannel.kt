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
 * Канал «по локальной сети»: прямое TCP-соединение с простой кадровкой.
 *
 * Кадр: [1 байт тип][4 байта длина][payload]. Тип 1 — JSON, тип 2 — JPEG.
 */
class LanChannel(private val socket: Socket) : BasePeerChannel() {

    override val remoteAddress: String?
        get() = runCatching { socket.inetAddress?.hostAddress }.getOrNull()

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER))
    private val writeLock = Any()
    private val outbox = Channel<Outgoing>(capacity = OUTBOX_CAPACITY)

    private var readJob: Job? = null
    private var writeJob: Job? = null

    private class Outgoing(val type: Int, val payload: ByteArray)

    init {
        runCatching { socket.tcpNoDelay = true }
        markOpen()
        readJob = scope.launch { readLoop() }
        writeJob = scope.launch { writeLoop() }
    }

    private suspend fun readLoop() {
        try {
            while (isOpen) {
                val type = input.readUnsignedByte()
                val length = input.readInt()
                if (length <= 0 || length > MAX_PAYLOAD) {
                    throw IOException("Некорректный размер кадра: $length")
                }
                val payload = ByteArray(length)
                input.readFully(payload)
                when (type) {
                    TYPE_JSON -> runCatching {
                        JSONObject(String(payload, Charsets.UTF_8))
                    }.getOrNull()?.let { deliverText(it) }
                    TYPE_FRAME -> postFrame(payload)
                    TYPE_VIDEO -> postVideo(payload)
                }
            }
        } catch (e: Exception) {
            if (isOpen) close(e.message ?: "Соединение потеряно")
        }
    }

    private suspend fun writeLoop() {
        for (item in outbox) {
            if (!isOpen) break
            try {
                synchronized(writeLock) {
                    output.writeByte(item.type)
                    output.writeInt(item.payload.size)
                    output.write(item.payload)
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
            scope.launch { outbox.send(item) }
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
 * Принимает входящие подключения в локальной сети (режим «принимаю подключение»).
 */
class LanServer(
    private val port: Int,
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
                    val channel = LanChannel(client)
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

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    private companion object {
        const val TAG = "LumenLanServer"

        /** Максимум одновременных входящих соединений. */
        const val MAX_CLIENTS = 4
    }
}

object LanClient {

    suspend fun connect(host: String, port: Int, timeoutMs: Int = 8000): PeerChannel =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host.trim(), port), timeoutMs)
                LanChannel(socket)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
}
