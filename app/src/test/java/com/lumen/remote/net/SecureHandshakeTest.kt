package com.lumen.remote.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверки рукопожатия защищённого канала: два «участника» в одном тесте. */
class SecureHandshakeTest {

    private val pin = "406755"

    @Test
    fun `handshake succeeds with same pin`() {
        val start = SecureHandshake.clientStart()
        val host = SecureHandshake.hostAccept(start.message, pin)
        assertNotNull(host)
        val (hostKeys, okMessage) = host!!

        val client = SecureHandshake.clientConfirm(start, okMessage, pin)
        assertNotNull(client)
        val clientKeys = client!!.keys
        val authMessage = client.message

        assertTrue(SecureHandshake.hostVerify(authMessage, start.message, okMessage, hostKeys))
        assertArrayEquals(hostKeys.session, clientKeys.session)
        assertArrayEquals(hostKeys.auth, clientKeys.auth)
    }

    @Test
    fun `client rejects host with other pin`() {
        val start = SecureHandshake.clientStart()
        val host = SecureHandshake.hostAccept(start.message, "111111")!!
        assertNull(SecureHandshake.clientConfirm(start, host.second, pin))
    }

    @Test
    fun `host rejects client with other pin`() {
        val start = SecureHandshake.clientStart()
        val host = SecureHandshake.hostAccept(start.message, pin)!!
        val client = SecureHandshake.clientConfirm(start, host.second, "999999")
        // Клиент сам обнаружит подмену: подтверждение хоста не сойдётся.
        assertNull(client)
    }

    @Test
    fun `tampered proof is rejected`() {
        val start = SecureHandshake.clientStart()
        val host = SecureHandshake.hostAccept(start.message, pin)!!
        val ok = host.second
        val client = SecureHandshake.clientConfirm(start, ok, pin)!!

        val tampered = HashMap(client.message)
        tampered["proof"] = Base64Codec.encode(LanCrypto.randomBytes(32))
        assertTrue(!SecureHandshake.hostVerify(tampered, start.message, ok, host.first))
    }

    @Test
    fun `tampered host key is rejected`() {
        val start = SecureHandshake.clientStart()
        val host = SecureHandshake.hostAccept(start.message, pin)!!
        val ok = HashMap(host.second)
        ok["kx"] = LanCrypto.encodePublicKey(LanCrypto.newKeyPair().public)
        assertNull(SecureHandshake.clientConfirm(start, ok, pin))
    }
}
