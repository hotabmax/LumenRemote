package com.lumen.remote.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

/**
 * Общая часть реализации [PeerChannel]: очередь входящих сообщений,
 * поток событий жизненного цикла и флаг открытого соединения.
 *
 * Наследники отвечают только за транспорт (TCP-сокет или WebSocket).
 */
abstract class BasePeerChannel : PeerChannel {

    protected val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val messageChannel = Channel<Incoming>(capacity = INCOMING_CAPACITY)
    final override val incoming: ReceiveChannel<Incoming> get() = messageChannel

    private val eventFlow = MutableSharedFlow<ChannelEvent>(replay = 1, extraBufferCapacity = 8)
    final override val events: SharedFlow<ChannelEvent> = eventFlow.asSharedFlow()

    @Volatile
    private var open = false
    final override val isOpen: Boolean get() = open

    /** Транспорт готов к обмену сообщениями. */
    protected fun markOpen() {
        if (open) return
        open = true
        eventFlow.tryEmit(ChannelEvent.Open)
    }

    /** Текстовое сообщение без потери: управляющие команды важнее кадров. */
    protected suspend fun deliverText(json: JSONObject) {
        if (!open) return
        if (messageChannel.trySend(Incoming.Text(json)).isFailure) {
            messageChannel.send(Incoming.Text(json))
        }
    }

    /** Текстовое сообщение без ожидания (используется в колбэках транспорта). */
    protected fun postText(json: JSONObject) {
        if (!open) return
        messageChannel.trySend(Incoming.Text(json))
    }

    /** Кадр экрана: при переполнении очереди кадр отбрасывается. */
    protected fun postFrame(jpeg: ByteArray) {
        if (!open) return
        messageChannel.trySend(Incoming.Frame(jpeg))
    }

    /** Видеопакет H.264 (конфигурация или кадр). */
    protected fun postVideo(packet: ByteArray) {
        if (!open) return
        messageChannel.trySend(Incoming.Video(packet))
    }

    /** Идемпотентно завершает канал, публикуя указанное событие. */
    protected fun finish(reason: String?, event: ChannelEvent) {
        val wasOpen = open
        open = false
        messageChannel.close()
        if (wasOpen) eventFlow.tryEmit(event)
    }

    protected fun finish(reason: String?) = finish(reason, ChannelEvent.Closed(reason))

    private companion object {
        const val INCOMING_CAPACITY = 32
    }
}
