package com.lumen.remote.net

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /** IPv4-адрес устройства в локальной сети. */
    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList().asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    const val DEFAULT_PORT = 8731
    const val DISCOVERY_PORT = 8890
}
