package com.lumen.remote.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Канал «через интернет»: WebSocket-соединение с relay-сервером.
 *
 * Клиент отправляет {"t":"host"|"viewer","code","pin"} и ждёт {"t":"ready"}.
 * После сопряжения сервер прозрачно пересылает текстовые и бинарные сообщения.
 */
class RelayChannel private constructor(
    client: OkHttpClient,
    url: String,
    private val role: String,
    private val code: String,
    private val pin: String
) : BasePeerChannel() {

    private val ready = CompletableDeferred<Unit>()
    private val webSocket: WebSocket = client.newWebSocket(
        Request.Builder().url(url).build(),
        Listener()
    )

    suspend fun awaitReady(timeoutMs: Long = 15_000) {
        withTimeout(timeoutMs) { ready.await() }
    }

    /** Колбэки OkHttp: переводим их в события канала. */
    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val join = JSONObject().apply {
                put(Proto.T, role)
                put(Proto.K_CODE, code)
                put(Proto.K_PIN, pin)
            }
            webSocket.send(join.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (json.optString(Proto.T)) {
                SERVER_READY -> {
                    markOpen()
                    ready.complete(Unit)
                }
                SERVER_ERROR -> closeWithFailure(
                    json.optString(Proto.K_REASON).ifBlank { RELAY_ERROR }
                )
                else -> postText(json)
            }
        }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val payload = bytes.toByteArray()
        // Бинарные сообщения relay: 0x03 — префикс видеопакета H.264, иначе JPEG.
        if (payload.isNotEmpty() && payload[0] == VIDEO_MARKER) {
            postVideo(payload.copyOfRange(1, payload.size))
        } else {
            postFrame(payload)
        }
    }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val message = t.message ?: response?.message ?: "Соединение прервано"
            if (!ready.isCompleted) ready.completeExceptionally(t)
            finish(message, ChannelEvent.Failed(message))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(reason.ifBlank { null })
        }
    }

    override fun send(json: JSONObject): Boolean {
        if (!isOpen) return false
        return webSocket.send(json.toString())
    }

    override fun sendFrame(jpeg: ByteArray): Boolean {
        if (!isOpen) return false
        return webSocket.send(jpeg.toByteString())
    }

    override fun sendVideoFrame(packet: ByteArray): Boolean {
        if (!isOpen) return false
        val payload = ByteArray(packet.size + 1)
        payload[0] = VIDEO_MARKER
        packet.copyInto(payload, destinationOffset = 1)
        return webSocket.send(payload.toByteString())
    }

    override fun close(reason: String?) {
        if (isOpen) runCatching { webSocket.close(1000, reason) }
        finish(reason)
    }

    private fun closeWithFailure(reason: String) {
        if (!ready.isCompleted) ready.completeExceptionally(IOException(reason))
        runCatching { webSocket.close(1000, reason) }
        finish(reason, ChannelEvent.Failed(reason))
    }

    companion object {
        const val ROLE_HOST = "host"
        const val ROLE_VIEWER = "viewer"

        private const val SERVER_READY = "ready"
        private const val SERVER_ERROR = "error"
        private const val RELAY_ERROR = "relay_error"
        private const val VIDEO_MARKER: Byte = 0x03

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        fun isSupportedUrl(url: String): Boolean {
            val trimmed = url.trim()
            return trimmed.startsWith("ws://", ignoreCase = true) ||
                trimmed.startsWith("wss://", ignoreCase = true)
        }

        suspend fun connect(
            url: String,
            role: String,
            code: String,
            pin: String,
            timeoutMs: Long = 15_000
        ): RelayChannel {
            if (!isSupportedUrl(url)) throw IOException("Некорректный адрес сервера")
            val channel = RelayChannel(sharedClient, url.trim(), role, code, pin)
            channel.awaitReady(timeoutMs)
            return channel
        }

        fun shutdown() {
            runCatching { sharedClient.dispatcher.executorService.shutdown() }
        }
    }
}
