package com.lumen.remote.core

import java.security.SecureRandom

object Ids {

    /** Длина кода подключения (9 цифр). */
    const val CODE_LENGTH = 9

    /** Длина PIN-кода (6 цифр). */
    const val PIN_LENGTH = 6

    /** Минимальная длина PIN для совместимости со старыми настройками. */
    const val MIN_PIN_LENGTH = 4

    /** Максимальная длина PIN. */
    const val MAX_PIN_LENGTH = 6

    private const val CODE_MIN = 100_000_000
    private const val CODE_BOUND = 900_000_000
    private const val PIN_MIN = 100_000
    private const val PIN_BOUND = 900_000

    private val random = SecureRandom()

    /** 9-значный код подключения. */
    fun newCode(): String = (CODE_MIN + random.nextInt(CODE_BOUND)).toString()

    /** 6-значный PIN-код. */
    fun newPin(): String = (PIN_MIN + random.nextInt(PIN_BOUND)).toString()

    fun isCode(value: String): Boolean = value.filter { it.isDigit() }.length == CODE_LENGTH

    fun isPin(value: String): Boolean =
        value.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && value.all { it.isDigit() }

    fun formatCode(code: String): String {
        val digits = code.filter { it.isDigit() }
        if (digits.length != CODE_LENGTH) return code
        return listOf(
            digits.substring(0, 3),
            digits.substring(3, 6),
            digits.substring(6, 9)
        ).joinToString(" ")
    }

    fun isLanAddress(value: String): Boolean {
        val trimmed = value.trim()
        return IPV4.matches(trimmed) || trimmed.contains(":")
    }

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
}
