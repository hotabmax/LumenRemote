package com.lumen.remote.net

import java.util.Base64 as JvmBase64

/**
 * Кодирование Base64 для рукопожатия.
 *
 * На устройстве используется `android.util.Base64` (доступен с API 1), а в
 * JVM-тестах платформенная заглушка бросает исключение — тогда берётся
 * `java.util.Base64`. Так протокол проверяется обычными юнит-тестами.
 */
internal object Base64Codec {

    fun encode(bytes: ByteArray): String = runCatching {
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }.getOrElse {
        JvmBase64.getEncoder().encodeToString(bytes)
    }

    fun decode(text: String): ByteArray = runCatching {
        android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
    }.getOrElse {
        JvmBase64.getDecoder().decode(text)
    }
}
