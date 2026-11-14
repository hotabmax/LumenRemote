package com.lumen.remote.net

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/** Интеграционная проверка шифрованных кадров на реальных сокетах. */
class LanChannelTest {

    private fun channel(
        socket: Socket,
        key: ByteArray?,
        direction: Byte,
        peerDirection: Byte
    ): LanChannel = LanChannel(
        socket = socket,
        input = DataInputStream(BufferedInputStream(socket.getInputStream())),
        output = DataOutputStream(BufferedOutputStream(socket.getOutputStream())),
        sessionKey = key,
        direction = direction,
        peerDirection = peerDirection,
        prefetched = null,
        initialOutCounter = 0
    )

    @Test
    fun `encrypted frames arrive intact`() = runBlocking {
        val key = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        ServerSocket(0).use { server ->
            val clientSocket = Socket("127.0.0.1", server.localPort)
            val serverSocket = server.accept()
            val host = channel(serverSocket, key, LanCrypto.DIR_HOST, LanCrypto.DIR_CLIENT)
            val viewer = channel(clientSocket, key, LanCrypto.DIR_CLIENT, LanCrypto.DIR_HOST)
            try {
                val payload = ByteArray(512) { (it % 251).toByte() }
                host.sendFrame(payload)
                val message = withTimeout(5_000) { viewer.incoming.receive() }
                assertTrue(message is Incoming.Frame)
                assertArrayEquals(payload, (message as Incoming.Frame).jpeg)
            } finally {
                host.close("done")
                viewer.close("done")
            }
        }
    }

    @Test
    fun `frames with other key are rejected`() = runBlocking {
        val key = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val otherKey = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        ServerSocket(0).use { server ->
            val clientSocket = Socket("127.0.0.1", server.localPort)
            val serverSocket = server.accept()
            val host = channel(serverSocket, key, LanCrypto.DIR_HOST, LanCrypto.DIR_CLIENT)
            val viewer = channel(clientSocket, otherKey, LanCrypto.DIR_CLIENT, LanCrypto.DIR_HOST)
            try {
                host.sendFrame(byteArrayOf(1, 2, 3, 4))
                val received = runCatching {
                    withTimeout(1_500) { viewer.incoming.receive() }
                }.getOrNull()
                assertTrue(received == null)
            } finally {
                host.close("done")
                viewer.close("done")
            }
        }
    }
}
