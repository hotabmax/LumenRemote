package com.lumen.remote.net

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Рукопожатие на «сыром» сокете до создания [LanChannel].
 *
 * Клиент инициирует обмен, хост отвечает. При успехе обе стороны получают ключ
 * сеанса и дальше шифруют каждый кадр (AES-GCM). Если партнёр старый и не умеет
 * рукопожатие, соединение продолжается без шифрования — это фиксируется в
 * состоянии канала, чтобы интерфейс мог предупредить пользователя.
 */
object LanHandshake {

    /** Кадр протокола, прочитанный целиком. */
    class Frame(val type: Int, val payload: ByteArray)

    /** Результат рукопожатия на стороне хоста. */
    class HostResult(
        val key: ByteArray?,
        val prefetched: Frame?,
        val outCounterStart: Long
    )

    private const val TYPE_JSON = 1
    private const val MAX_FRAME = 8 * 1024 * 1024
    private const val HANDSHAKE_TIMEOUT_MS = 6_000

    /**
     * Клиентское рукопожатие.
     *
     * @return ключ сеанса или null, если хост не поддерживает шифрование.
     */
    fun client(
        input: DataInputStream,
        output: DataOutputStream,
        pin: String,
        timeoutMs: Int = HANDSHAKE_TIMEOUT_MS
    ): ByteArray? {
        val start = SecureHandshake.clientStart()
        writeFrame(output, TYPE_JSON, jsonFrom(start.message).toString().toByteArray())

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val frame = runCatching { readFrame(input) }.getOrNull() ?: return null
            if (frame.type != TYPE_JSON) continue
            val json = parse(frame.payload) ?: continue
            when (json.optString(SecureHandshake.T)) {
                SecureHandshake.CRYPTO_OK -> {
                    val confirm = SecureHandshake.clientConfirm(start, mapFrom(json), pin)
                        ?: return null
                    writeFrame(
                        output,
                        TYPE_JSON,
                        jsonFrom(confirm.message).toString().toByteArray()
                    )
                    return confirm.keys.session
                }
                SecureHandshake.CRYPTO_FAIL -> return null
                else -> Unit
            }
        }
        return null
    }

    /**
     * Хостовое рукопожатие: ждёт `hello_crypto` от клиента.
     *
     * Если первым пришёл обычный кадр (старый клиент), он возвращается в
     * [HostResult.prefetched], чтобы канал обработал его как обычно.
     */
    fun host(
        input: DataInputStream,
        output: DataOutputStream,
        pinProvider: () -> String,
        timeoutMs: Int = HANDSHAKE_TIMEOUT_MS
    ): HostResult {
        val first = runCatching { readFrame(input) }.getOrNull()
            ?: return HostResult(null, null, 0)
        val helloJson = if (first.type == TYPE_JSON) parse(first.payload) else null
        if (helloJson == null || helloJson.optString(SecureHandshake.T) != SecureHandshake.HELLO_CRYPTO) {
            return HostResult(null, first, 0)
        }
        val hello = mapFrom(helloJson)

        val accepted = SecureHandshake.hostAccept(hello, pinProvider())
        if (accepted == null) {
            writeFail(output)
            return HostResult(null, null, 0)
        }
        val (keys, okMessage) = accepted
        writeFrame(output, TYPE_JSON, jsonFrom(okMessage).toString().toByteArray())

        val authFrame = runCatching { readFrame(input) }.getOrNull()
        val authJson = authFrame?.let { parse(it.payload) }
        val auth = authJson?.let { mapFrom(it) }
        if (auth == null || !SecureHandshake.hostVerify(auth, hello, okMessage, keys)) {
            writeFail(output)
            return HostResult(null, null, 0)
        }

        // Первый зашифрованный кадр: подтверждение готовности с нулевым счётчиком.
        val ready = JSONObject().apply { put("t", SecureHandshake.CRYPTO_READY) }
        val sealed = LanCrypto.seal(
            keys.session,
            LanCrypto.nonce(LanCrypto.DIR_HOST, 0),
            TYPE_JSON,
            ready.toString().toByteArray()
        )
        writeFrame(output, TYPE_JSON, sealed)
        return HostResult(keys.session, null, 1)
    }

    /** Помечает сокет таймаутом на время рукопожатия. */
    fun withHandshakeTimeout(socket: Socket, block: () -> Unit) {
        val previous = runCatching { socket.soTimeout }.getOrDefault(0)
        runCatching { socket.soTimeout = HANDSHAKE_TIMEOUT_MS }
        try {
            block()
        } catch (timeout: SocketTimeoutException) {
            // Партнёр молчит — считаем, что шифрование не поддерживается.
        } finally {
            runCatching { socket.soTimeout = previous }
        }
    }

    private fun writeFail(output: DataOutputStream) {
        val json = JSONObject().apply { put("t", SecureHandshake.CRYPTO_FAIL) }
        runCatching { writeFrame(output, TYPE_JSON, json.toString().toByteArray()) }
    }

    private fun parse(payload: ByteArray): JSONObject? =
        runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()

    /** Карта рукопожатия → JSON для отправки. */
    private fun jsonFrom(message: Map<String, String>): JSONObject {
        val json = JSONObject()
        for ((key, value) in message) json.put(key, value)
        return json
    }

    /** JSON → карта для разбора в [SecureHandshake]. */
    private fun mapFrom(json: JSONObject): Map<String, String> {
        val result = HashMap<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = json.optString(key)
        }
        return result
    }

    /** Читает кадр [type][len][payload]. */
    fun readFrame(input: DataInputStream): Frame {
        val type = input.readUnsignedByte()
        val length = input.readInt()
        require(length in 1..MAX_FRAME) { "Некорректный размер кадра: $length" }
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(type, payload)
    }

    /** Пишет кадр [type][len][payload]. */
    fun writeFrame(output: DataOutputStream, type: Int, payload: ByteArray) {
        output.writeByte(type)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }
}
