package com.lumen.remote.core

import com.lumen.remote.BuildConfig
import com.lumen.remote.net.RelayChannel

/**
 * Политика безопасности, зависящая от типа сборки.
 *
 * Release: cleartext запрещён манифестом, поэтому relay допускается только по `wss://`.
 * Debug: разрешён `ws://` для локальных тестов и самоподписанных серверов.
 */
object BuildFlags {

    val allowCleartextRelay: Boolean = BuildConfig.DEBUG

    fun isRelayUrlAllowed(url: String): Boolean {
        val trimmed = url.trim()
        if (!RelayChannel.isSupportedUrl(trimmed)) return false
        return allowCleartextRelay || trimmed.startsWith("wss://", ignoreCase = true)
    }
}
