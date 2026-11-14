package com.lumen.remote.core

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class DeviceRecord(
    val id: String,
    val name: String,
    val platform: String,
    val mode: String,
    val address: String,
    val lastConnectedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("platform", platform)
        put("mode", mode)
        put("address", address)
        put("ts", lastConnectedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = DeviceRecord(
            id = o.optString("id"),
            name = o.optString("name", "Устройство"),
            platform = o.optString("platform", "Android"),
            mode = o.optString("mode", Prefs.MODE_LAN),
            address = o.optString("address"),
            lastConnectedAt = o.optLong("ts", 0L)
        )
    }
}

/**
 * Локальное хранилище настроек и сохранённых устройств.
 */
class Prefs private constructor(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var deviceName: String
        get() = sp.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName()
        set(value) = sp.edit().putString(KEY_NAME, value).apply()

    var myId: String
        get() {
            val stored = sp.getString(KEY_ID, null)
            if (stored != null && stored.length == Ids.CODE_LENGTH) return stored
            val generated = Ids.newCode()
            sp.edit().putString(KEY_ID, generated).apply()
            return generated
        }
        set(value) = sp.edit().putString(KEY_ID, value).apply()

    var myPin: String
        get() {
            val stored = sp.getString(KEY_PIN, null)
            if (stored != null && stored.length in Ids.MIN_PIN_LENGTH..Ids.MAX_PIN_LENGTH) return stored
            val generated = Ids.newPin()
            sp.edit().putString(KEY_PIN, generated).apply()
            return generated
        }
        set(value) = sp.edit().putString(KEY_PIN, value).apply()

    var relayUrl: String
        get() = sp.getString(KEY_RELAY, DEFAULT_RELAY) ?: DEFAULT_RELAY
        set(value) = sp.edit().putString(KEY_RELAY, value.trim()).apply()

    /** Кадров в секунду при трансляции экрана (4..24). */
    var frameRate: Int
        get() = sp.getInt(KEY_FPS, 12).coerceIn(4, 24)
        set(value) = sp.edit().putInt(KEY_FPS, value.coerceIn(4, 24)).apply()

    /** Качество JPEG (30..90). */
    var quality: Int
        get() = sp.getInt(KEY_QUALITY, 62).coerceIn(30, 90)
        set(value) = sp.edit().putInt(KEY_QUALITY, value.coerceIn(30, 90)).apply()

    var requirePin: Boolean
        get() = sp.getBoolean(KEY_REQUIRE_PIN, true)
        set(value) = sp.edit().putBoolean(KEY_REQUIRE_PIN, value).apply()

    var allowClipboard: Boolean
        get() = sp.getBoolean(KEY_CLIPBOARD, true)
        set(value) = sp.edit().putBoolean(KEY_CLIPBOARD, value).apply()

    var confirmTouch: Boolean
        get() = sp.getBoolean(KEY_CONFIRM_TOUCH, false)
        set(value) = sp.edit().putBoolean(KEY_CONFIRM_TOUCH, value).apply()

    /** Спрашивать подтверждение при каждом подключении партнёра. */
    var confirmConnections: Boolean
        get() = sp.getBoolean(KEY_CONFIRM_CONNECTION, true)
        set(value) = sp.edit().putBoolean(KEY_CONFIRM_CONNECTION, value).apply()

    /** Показывать устройство в локальной сети (UDP-обнаружение). */
    var discoverable: Boolean
        get() = sp.getBoolean(KEY_DISCOVERABLE, true)
        set(value) = sp.edit().putBoolean(KEY_DISCOVERABLE, value).apply()

    fun savedDevices(): List<DeviceRecord> {
        val raw = sp.getString(KEY_DEVICES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { DeviceRecord.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.lastConnectedAt }
    }

    fun upsertDevice(record: DeviceRecord) {
        val devices = savedDevices().filterNot { it.id == record.id }.toMutableList()
        devices.add(0, record)
        writeDevices(devices.take(24))
    }

    fun removeDevice(id: String) {
        writeDevices(savedDevices().filterNot { it.id == id })
    }

    private fun writeDevices(devices: List<DeviceRecord>) {
        val array = JSONArray()
        devices.forEach { array.put(it.toJson()) }
        sp.edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    private fun defaultName(): String {
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        return model.replaceFirstChar { it.uppercase() }
    }

    companion object {
        const val MODE_LAN = "lan"
        const val MODE_RELAY = "relay"
        const val DEFAULT_RELAY = ""

        private const val NAME = "lumen_prefs"
        private const val KEY_NAME = "device_name"
        private const val KEY_ID = "my_id"
        private const val KEY_PIN = "my_pin"
        private const val KEY_RELAY = "relay_url"
        private const val KEY_FPS = "frame_rate"
        private const val KEY_QUALITY = "jpeg_quality"
        private const val KEY_REQUIRE_PIN = "require_pin"
        private const val KEY_CLIPBOARD = "allow_clipboard"
        private const val KEY_CONFIRM_TOUCH = "confirm_touch"
        private const val KEY_CONFIRM_CONNECTION = "confirm_connection"
        private const val KEY_DISCOVERABLE = "discoverable"
        private const val KEY_DEVICES = "devices"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}
