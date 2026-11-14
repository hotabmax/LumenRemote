package com.lumen.remote.host

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Защита от перебора PIN-кода.
 *
 * Считает неудачные попытки по адресу партнёра и включает нарастающую блокировку:
 * 5 ошибок — 30 секунд, 8 — 5 минут, 12 — час. Успешный вход сбрасывает счётчик.
 * Это делает перебор 6-значного PIN нецелесообразным по времени.
 */
object AuthGuard {

    private const val TAG = "LumenAuthGuard"

    private const val FIRST_LEVEL = 5
    private const val SECOND_LEVEL = 8
    private const val THIRD_LEVEL = 12

    private const val SHORT_BLOCK_MS = 30_000L
    private const val MEDIUM_BLOCK_MS = 5 * 60_000L
    private const val LONG_BLOCK_MS = 60 * 60_000L

    /** Через сколько записей без активности адрес забывается. */
    private const val ENTRY_TTL_MS = 6 * 60 * 60_000L

    private class Entry(var failures: Int = 0, var blockedUntil: Long = 0L, var lastSeen: Long = 0L)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Сколько миллисекунд осталось до конца блокировки; 0 — можно пробовать. */
    fun blockRemainingMs(key: String): Long {
        val entry = entries[key] ?: return 0L
        val left = entry.blockedUntil - System.currentTimeMillis()
        return if (left > 0) left else 0L
    }

    /** Учитывает неудачную попытку входа. */
    fun registerFailure(key: String): Long {
        val now = System.currentTimeMillis()
        val entry = entries.getOrPut(key) { Entry() }
        synchronized(entry) {
            entry.failures += 1
            entry.lastSeen = now
            entry.blockedUntil = when {
                entry.failures >= THIRD_LEVEL -> now + LONG_BLOCK_MS
                entry.failures >= SECOND_LEVEL -> now + MEDIUM_BLOCK_MS
                entry.failures >= FIRST_LEVEL -> now + SHORT_BLOCK_MS
                else -> entry.blockedUntil
            }
            val remaining = entry.blockedUntil - now
            Log.w(TAG, "Неудачный вход ($key), попыток: ${entry.failures}")
            return if (remaining > 0) remaining else 0L
        }
    }

    /** Сбрасывает счётчик после успешного входа. */
    fun registerSuccess(key: String) {
        entries.remove(key)
    }

    /** Удаляет давно неактивные записи, чтобы карта не росла бесконечно. */
    fun prune() {
        val threshold = System.currentTimeMillis() - ENTRY_TTL_MS
        entries.entries.removeIf { (_, entry) -> entry.lastSeen < threshold }
    }
}
