package com.lumen.remote.ui.viewer

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.lumen.remote.net.Proto
import java.nio.ByteBuffer

/**
 * Аппаратный H.264-декодер для просмотра потока с принимающего устройства.
 *
 * Пакеты приходят с префиксом типа: [Proto.VIDEO_KIND_CONFIG] — SPS/PPS,
 * [Proto.VIDEO_KIND_FRAME] — кадр. Рендер идёт прямо в [Surface] (SurfaceView),
 * без YUV→RGB конвертации.
 */
class H264Decoder(
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val onVideoSize: (width: Int, height: Int) -> Unit
) {

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var csd: ByteArray? = null

    @Volatile
    private var started = false

    private var configuredWidth = 0
    private var configuredHeight = 0

    /** SurfaceView готов (или уничтожен). */
    fun setSurface(value: Surface?) {
        surface = value
        if (value == null) {
            release()
        } else {
            startIfReady(force = true)
        }
    }

    /** Пришёл конфигурационный пакет (SPS/PPS) — (пере)запускаем декодер. */
    fun onConfig(data: ByteArray) {
        csd = data
        startIfReady(force = true)
    }

    /** Пришёл кадр H.264. */
    fun onFrame(data: ByteArray) {
        if (!started) return
        val decoder = codec ?: return
        try {
            val inputIndex = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex >= 0) {
                val buffer = decoder.getInputBuffer(inputIndex)
                if (buffer != null) {
                    buffer.clear()
                    buffer.put(data)
                }
                decoder.queueInputBuffer(inputIndex, 0, data.size, System.nanoTime() / 1000, 0)
            }
            drain(decoder)
        } catch (throwable: Throwable) {
            Log.w(TAG, "Кадр не принят: ${throwable.message}")
        }
    }

    private fun startIfReady(force: Boolean) {
        val target = surface ?: return
        val config = csd ?: return
        if (started && !force) return
        if (started && force && configuredWidth == videoWidth && configuredHeight == videoHeight) return

        release()
        val width = videoWidth.coerceAtLeast(2)
        val height = videoHeight.coerceAtLeast(2)
        val (sps, pps) = splitCsd(config)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            if (pps != null) setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        }

        val decoder = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (throwable: Throwable) {
            Log.w(TAG, "H.264-декодер недоступен: ${throwable.message}")
            return
        }

        try {
            decoder.configure(format, target, null, 0)
            decoder.start()
        } catch (throwable: Throwable) {
            Log.w(TAG, "Не удалось запустить декодер: ${throwable.message}")
            runCatching { decoder.release() }
            return
        }

        codec = decoder
        configuredWidth = width
        configuredHeight = height
        started = true
        Log.i(TAG, "Декодер H.264 запущен ${width}x$height")
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    val width = format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    onVideoSize(width, height)
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> if (index >= 0) {
                    decoder.releaseOutputBuffer(index, true)
                } else {
                    return
                }
            }
        }
    }

    fun release() {
        started = false
        configuredWidth = 0
        configuredHeight = 0
        codec?.let { decoder ->
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
        codec = null
    }

    /**
     * Делит пакет конфигурации (SPS+PPS в Annex B) на `csd-0` и `csd-1`.
     * Часть аппаратных декодеров не понимает склеенные SPS и PPS в одном буфере.
     */
    private fun splitCsd(data: ByteArray): Pair<ByteArray, ByteArray?> {
        val offsets = startCodeOffsets(data)
        if (offsets.size < 2) return data to null
        val end = if (offsets.size > 2) offsets[2] else data.size
        return data.copyOfRange(offsets[0], offsets[1]) to data.copyOfRange(offsets[1], end)
    }

    /** Позиции стартовых кодов NAL (`00 00 01` / `00 00 00 01`). */
    private fun startCodeOffsets(data: ByteArray): List<Int> {
        val offsets = ArrayList<Int>(4)
        var index = 0
        while (index + 3 < data.size) {
            val isPrefix = data[index] == 0.toByte() && data[index + 1] == 0.toByte()
            if (!isPrefix) {
                index += 1
                continue
            }
            when {
                data[index + 2] == 1.toByte() -> {
                    offsets.add(index)
                    index += 3
                }
                data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte() -> {
                    offsets.add(index)
                    index += 4
                }
                else -> index += 1
            }
        }
        return offsets
    }

    private companion object {
        const val TAG = "LumenViewer"
        const val INPUT_TIMEOUT_US = 5_000L
    }
}
