package com.lumen.remote.net

import org.json.JSONObject

/**
 * Протокол обмена между устройствами.
 *
 * Управляющие сообщения — JSON, кадры экрана — бинарные JPEG-блоки.
 * Ключи и разбор сосредоточены здесь, чтобы обе стороны (приём и просмотр)
 * не дублировали «магические» строки.
 */
object Proto {

    // Служебные поля
    const val T = "t"
    const val K_CODE = "code"
    const val K_PIN = "pin"
    const val K_REASON = "reason"
    const val K_NAME = "name"
    const val K_PLATFORM = "platform"
    const val K_MODEL = "model"
    const val K_WIDTH = "w"
    const val K_HEIGHT = "h"
    const val K_CONTROL = "control"
    const val K_TS = "ts"

    // Координаты жестов
    const val K_X = "x"
    const val K_Y = "y"
    const val K_X1 = "x1"
    const val K_Y1 = "y1"
    const val K_X2 = "x2"
    const val K_Y2 = "y2"
    const val K_DURATION = "ms"

    // Ввод и качество
    const val K_KEY = "k"
    const val K_TEXT = "s"
    const val K_FPS = "fps"
    const val K_QUALITY = "q"

    // Диагностика захвата
    const val K_FRAMES = "frames"
    const val K_ENCODED = "encoded"
    const val K_VISIBLE = "visible"
    const val K_CAPTURE = "capture"

    // Кодеки видео
    const val K_CODECS = "codecs"
    const val CODEC_H264 = "h264"
    const val CODEC_JPEG = "jpeg"
    const val VIDEO_KIND_CONFIG = 1
    const val VIDEO_KIND_FRAME = 0

    // Типы сообщений
    const val HELLO = "hello"
    const val AUTH = "auth"
    const val AUTH_OK = "auth_ok"
    const val AUTH_FAIL = "auth_fail"
    const val TAP = "tap"
    const val SWIPE = "swipe"
    const val KEY = "key"
    const val TEXT = "text"
    const val QUALITY = "quality"
    const val STATUS = "status"
    const val PING = "ping"
    const val PONG = "pong"
    const val BYE = "bye"
    const val PEER_LEFT = "peer_left"
    const val CMD = "cmd"

    // Команды управления (работают и на iOS, где жесты недоступны)
    const val K_ACTION = "action"
    const val K_VALUE = "value"
    const val ACTION_OPEN_URL = "open_url"
    const val ACTION_CLIPBOARD = "clipboard"

    // Клавиши удалённого устройства
    const val KEY_BACK = "back"
    const val KEY_HOME = "home"
    const val KEY_RECENTS = "recents"
    const val KEY_NOTIFICATIONS = "notifications"
    const val KEY_ENTER = "enter"

    data class Hello(
        val name: String,
        val platform: String,
        val model: String,
        val width: Int,
        val height: Int,
        val control: Boolean,
        val pinRequired: Boolean,
        val codecs: String = ""
    )

    data class Tap(val x: Float, val y: Float)

    data class Swipe(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Int
    )

    data class Quality(val fps: Int, val quality: Int)

    data class Cmd(val action: String, val value: String)

    /** Диагностика захвата: важно для разбора чёрного экрана на Huawei. */
    data class Status(
        val framesSent: Long,
        val framesEncoded: Long,
        val width: Int,
        val height: Int,
        val visible: Boolean,
        val captureActive: Boolean
    )

    fun hello(
        name: String,
        platform: String,
        model: String,
        width: Int,
        height: Int,
        control: Boolean,
        pinRequired: Boolean,
        codecs: String = ""
    ): JSONObject = JSONObject().apply {
        put(T, HELLO)
        put(K_NAME, name)
        put(K_PLATFORM, platform)
        put(K_MODEL, model)
        put(K_WIDTH, width)
        put(K_HEIGHT, height)
        put(K_CONTROL, control)
        put(K_PIN, pinRequired)
        if (codecs.isNotEmpty()) put(K_CODECS, codecs)
    }

    fun auth(pin: String): JSONObject = JSONObject().apply {
        put(T, AUTH)
        put(K_PIN, pin)
    }

    fun tap(x: Float, y: Float): JSONObject = JSONObject().apply {
        put(T, TAP)
        put(K_X, x.toDouble())
        put(K_Y, y.toDouble())
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int): JSONObject =
        JSONObject().apply {
            put(T, SWIPE)
            put(K_X1, x1.toDouble())
            put(K_Y1, y1.toDouble())
            put(K_X2, x2.toDouble())
            put(K_Y2, y2.toDouble())
            put(K_DURATION, durationMs)
        }

    fun key(key: String): JSONObject = JSONObject().apply {
        put(T, KEY)
        put(K_KEY, key)
    }

    fun text(value: String): JSONObject = JSONObject().apply {
        put(T, TEXT)
        put(K_TEXT, value)
    }

    fun quality(fps: Int, jpegQuality: Int): JSONObject = JSONObject().apply {
        put(T, QUALITY)
        put(K_FPS, fps)
        put(K_QUALITY, jpegQuality)
    }

    fun status(status: Status): JSONObject = JSONObject().apply {
        put(T, STATUS)
        put(K_FRAMES, status.framesSent)
        put(K_ENCODED, status.framesEncoded)
        put(K_WIDTH, status.width)
        put(K_HEIGHT, status.height)
        put(K_VISIBLE, status.visible)
        put(K_CAPTURE, status.captureActive)
    }

    fun ping(): JSONObject = JSONObject().apply {
        put(T, PING)
        put(K_TS, System.currentTimeMillis())
    }

    fun pong(timestamp: Long): JSONObject = JSONObject().apply {
        put(T, PONG)
        put(K_TS, timestamp)
    }

    fun bye(): JSONObject = JSONObject().apply { put(T, BYE) }

    /** Команда-действие: работает там, где недоступна инъекция жестов (iOS). */
    fun cmd(action: String, value: String = ""): JSONObject = JSONObject().apply {
        put(T, CMD)
        put(K_ACTION, action)
        if (value.isNotEmpty()) put(K_VALUE, value)
    }

    fun type(json: JSONObject): String = json.optString(T, "")

    fun parseHello(json: JSONObject): Hello = Hello(
        name = json.optString(K_NAME).ifBlank { "Устройство" },
        platform = json.optString(K_PLATFORM).ifBlank { "Android" },
        model = json.optString(K_MODEL).ifBlank { "Android" },
        width = json.optInt(K_WIDTH, 0),
        height = json.optInt(K_HEIGHT, 0),
        control = json.optBoolean(K_CONTROL, false),
        pinRequired = json.optBoolean(K_PIN, true),
        codecs = json.optString(K_CODECS, "")
    )

    fun parseTap(json: JSONObject): Tap = Tap(
        x = json.optDouble(K_X, 0.0).toFloat(),
        y = json.optDouble(K_Y, 0.0).toFloat()
    )

    fun parseSwipe(json: JSONObject): Swipe = Swipe(
        x1 = json.optDouble(K_X1, 0.0).toFloat(),
        y1 = json.optDouble(K_Y1, 0.0).toFloat(),
        x2 = json.optDouble(K_X2, 0.0).toFloat(),
        y2 = json.optDouble(K_Y2, 0.0).toFloat(),
        durationMs = json.optInt(K_DURATION, 200)
    )

    fun parseQuality(json: JSONObject, defaultFps: Int, defaultQuality: Int): Quality = Quality(
        fps = json.optInt(K_FPS, defaultFps).coerceIn(MIN_FPS, MAX_FPS),
        quality = json.optInt(K_QUALITY, defaultQuality).coerceIn(MIN_QUALITY, MAX_QUALITY)
    )

    fun parseStatus(json: JSONObject): Status = Status(
        framesSent = json.optLong(K_FRAMES, 0),
        framesEncoded = json.optLong(K_ENCODED, 0),
        width = json.optInt(K_WIDTH, 0),
        height = json.optInt(K_HEIGHT, 0),
        visible = json.optBoolean(K_VISIBLE, true),
        captureActive = json.optBoolean(K_CAPTURE, false)
    )

    fun parsePin(json: JSONObject): String = json.optString(K_PIN)

    fun parseKey(json: JSONObject): String = json.optString(K_KEY)

    fun parseText(json: JSONObject): String = json.optString(K_TEXT)

    fun parseTimestamp(json: JSONObject): Long = json.optLong(K_TS, 0L)

    fun parseCmd(json: JSONObject): Cmd = Cmd(
        action = json.optString(K_ACTION),
        value = json.optString(K_VALUE)
    )

    const val MIN_FPS = 4
    const val MAX_FPS = 24
    const val MIN_QUALITY = 30
    const val MAX_QUALITY = 90
}
