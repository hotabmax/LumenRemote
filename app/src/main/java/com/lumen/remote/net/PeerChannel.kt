package com.lumen.remote.net

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

sealed class ChannelEvent {
    data object Open : ChannelEvent()
    data class Closed(val reason: String? = null) : ChannelEvent()
    data class Failed(val message: String?) : ChannelEvent()
}

sealed class Incoming {
    class Text(val json: JSONObject) : Incoming()
    class Frame(val jpeg: ByteArray) : Incoming()

    /** Видеокадр H.264: [1 байт тип пакета][данные]. */
    class Video(val packet: ByteArray) : Incoming()
}

/**
 * Одно соединение с партнёром: управляющие JSON-сообщения и бинарные кадры экрана.
 */
interface PeerChannel {

    /** Входящие сообщения. Единственный потребитель — контроллер сеанса. */
    val incoming: ReceiveChannel<Incoming>

    /** События жизненного цикла соединения. */
    val events: SharedFlow<ChannelEvent>

    val isOpen: Boolean

    /** Канал шифруется (LAN-рукопожатие или wss). */
    val isSecure: Boolean get() = false

    /** Адрес партнёра для журнала и троттлинга попыток (LAN/IP, relay/код). */
    val remoteAddress: String? get() = null

    fun send(json: JSONObject): Boolean

    fun sendFrame(jpeg: ByteArray): Boolean

    /** Видеопакет H.264 (конфигурация или кадр). */
    fun sendVideoFrame(packet: ByteArray): Boolean

    fun close(reason: String? = null)
}
