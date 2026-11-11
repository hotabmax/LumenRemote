package com.lumen.remote.host

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.lumen.remote.net.Proto
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Аппаратный H.264-энкодер для MediaProjection.
 *
 * VirtualDisplay рисует прямо в [inputSurface] кодека (без ImageReader), а на выходе
 * получаются пакеты: [Proto.VIDEO_KIND_CONFIG] (SPS/PPS) и [Proto.VIDEO_KIND_FRAME].
 * Это обходит проблему EMUI/HarmonyOS, где ImageReader не отдаёт буферы.
 *
 * Весь жизненный цикл MediaCodec (создание, start, dequeue, release) выполняется
 * в одном выделенном потоке: медиакодек не переносит вызовы из разных потоков.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val onPacket: (kind: Int, data: ByteArray) -> Unit
) {

    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var codec: MediaCodec? = null

    /** Дренаж завершился ошибкой — сервис должен вернуться к JPEG. */
    @Volatile
    var failed: Boolean = false
        private set

    /** Сколько пакетов отдал кодек (для диагностики и сторожевого таймера). */
    @Volatile
    var packets: Long = 0L
        private set

    var inputSurface: Surface? = null
        private set

    /**
     * Запускает энкодер и ждёт готовности входной поверхности.
     *
     * @return true, если кодек стартовал и [inputSurface] можно отдавать дисплею.
     */
    fun start(): Boolean {
        if (running) return true
        if (width <= 0 || height <= 0) return false

        running = true
        failed = false
        val ready = CountDownLatch(1)
        thread = Thread({ encodeLoop(ready) }, "lumen-h264").also { it.start() }
        ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return inputSurface != null
    }

    private fun encodeLoop(ready: CountDownLatch) {
        val encoder = try {
            createEncoder()
        } catch (throwable: Throwable) {
            Log.w(TAG, "H.264-энкодер недоступен: ${throwable.message}")
            failed = true
            running = false
            ready.countDown()
            return
        }

        codec = encoder
        ready.countDown()

        try {
            drainLoop(encoder)
        } finally {
            codec = null
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            inputSurface?.let { surface -> runCatching { surface.release() } }
            inputSurface = null
        }
    }

    private fun createEncoder(): MediaCodec {
        val bitrate =
            (width.toLong() * height * fps * 0.08).toInt().coerceIn(500_000, 8_000_000)
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(Proto.MIN_FPS, Proto.MAX_FPS))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()
        } catch (throwable: Throwable) {
            Log.w(TAG, "Не удалось запустить H.264: ${throwable.message}")
            runCatching { encoder.release() }
            inputSurface = null
            failed = true
            running = false
            throw throwable
        }
        Log.i(TAG, "H.264 запущен ${width}x$height @ $fps к/с, ~$bitrate бит/с")
        return encoder
    }

    private fun drainLoop(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var errors = 0
        // Софт-кодеки успевают отдать первый буфер не сразу после start().
        runCatching { Thread.sleep(FIRST_DEQUEUE_DELAY_MS) }
        while (running) {
            val index = try {
                encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            } catch (throwable: Throwable) {
                errors += 1
                Log.w(
                    TAG,
                    "dequeue ошибка ${errors}/${MAX_DRAIN_ERRORS}: " +
                        "${throwable.javaClass.simpleName} ${throwable.message}"
                )
                if (errors >= MAX_DRAIN_ERRORS) {
                    failed = true
                    break
                }
                runCatching { Thread.sleep(DEQUEUE_RETRY_MS) }
                continue
            }
            errors = 0
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    buildCsd(encoder.outputFormat)?.let { csd ->
                        onPacket(Proto.VIDEO_KIND_CONFIG, csd)
                        packets += 1
                        Log.i(TAG, "CSD: ${csd.size} байт")
                    }
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(data)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val kind = if (isConfig) Proto.VIDEO_KIND_CONFIG else Proto.VIDEO_KIND_FRAME
                        onPacket(kind, data)
                        packets += 1
                        if (packets == 1L || packets % 30L == 0L) {
                            Log.i(TAG, "пакетов $packets, последний ${data.size} байт")
                        }
                    }
                    runCatching { encoder.releaseOutputBuffer(index, false) }
                }
            }
        }
        Log.i(TAG, "H.264 остановлен, отдано пакетов: $packets")
    }

    /** SPS/PPS из формата выхода: склеиваем csd-0 и csd-1. */
    private fun buildCsd(format: MediaFormat): ByteArray? {
        val sps = format.getByteBuffer("csd-0")?.toByteArray() ?: return null
        val pps = format.getByteBuffer("csd-1")?.toByteArray() ?: ByteArray(0)
        return sps + pps
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val data = ByteArray(remaining())
        get(data)
        return data
    }

    /** Просит кодек отдать ключевой кадр (после смены зрителя или реконнекта). */
    fun requestKeyFrame() {
        runCatching {
            codec?.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            )
        }
    }

    /** Останавливает энкодер; ресурсы освобождаются в его собственном потоке. */
    fun stop() {
        running = false
        thread?.let { worker -> runCatching { worker.join(STOP_TIMEOUT_MS) } }
        thread = null
    }

    private companion object {
        const val TAG = "LumenH264"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val FIRST_DEQUEUE_DELAY_MS = 40L
        const val DEQUEUE_RETRY_MS = 25L
        const val MAX_DRAIN_ERRORS = 40
        const val MIN_ENCODE_SIDE = 240
        const val START_TIMEOUT_MS = 2_000L
        const val STOP_TIMEOUT_MS = 1_000L
    }
}
