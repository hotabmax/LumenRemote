package com.lumen.remote.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Автообнаружение принимающих устройств в локальной сети через UDP-broadcast.
 */
object Discovery {

    data class Peer(
        val id: String,
        val name: String,
        val ip: String,
        val port: Int,
        val requiresPin: Boolean
    )

    private const val APP_TAG = "lumen"

    private var scope: CoroutineScope? = null
    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var listenSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun startBroadcast(context: Context, id: String, name: String, port: Int, requiresPin: Boolean) {
        stopBroadcast()
        val appContext = context.applicationContext
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = localScope
        val ip = NetUtils.localIp().orEmpty()
        broadcastJob = localScope.launch {
            val socket = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull() ?: return@launch
            try {
                val target = InetAddress.getByName("255.255.255.255")
                val payload = JSONObject().apply {
                    put("app", APP_TAG)
                    put("v", 1)
                    put("id", id)
                    put("name", name)
                    put("ip", ip)
                    put("port", port)
                    put("pin", requiresPin)
                }.toString().toByteArray()
                while (true) {
                    runCatching { socket.send(DatagramPacket(payload, payload.size, target, NetUtils.DISCOVERY_PORT)) }
                    delay(2500)
                }
            } finally {
                runCatching { socket.close() }
            }
        }
        acquireLock(appContext)
    }

    fun stopBroadcast() {
        broadcastJob?.cancel()
        broadcastJob = null
        scope?.cancel()
        scope = null
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
    }

    fun startListening(context: Context, onPeer: (Peer) -> Unit) {
        stopListening()
        acquireLock(context.applicationContext)
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        listenJob = localScope.launch {
            val socket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(NetUtils.DISCOVERY_PORT))
                }
            }.getOrNull() ?: return@launch
            listenSocket = socket
            val buffer = ByteArray(2048)
            try {
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(packet) }.onFailure { break }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    if (json.optString("app") != APP_TAG) continue
                    val peer = Peer(
                        id = json.optString("id"),
                        name = json.optString("name", "Android"),
                        ip = json.optString("ip").ifBlank { packet.address.hostAddress.orEmpty() },
                        port = json.optInt("port", NetUtils.DEFAULT_PORT),
                        requiresPin = json.optBoolean("pin", true)
                    )
                    if (peer.ip.isNotBlank()) onPeer(peer)
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        runCatching { listenSocket?.close() }
        listenSocket = null
    }

    private fun acquireLock(context: Context) {
        if (multicastLock != null) return
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("lumen_discovery")
            lock?.setReferenceCounted(true)
            lock?.acquire()
            multicastLock = lock
        }
    }
}
