package com.lumen.remote.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверки криптопримитивов защищённого канала. */
class LanCryptoTest {

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `hkdf matches rfc 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expected = hex(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"
        )
        assertArrayEquals(expected, LanCrypto.hkdf(ikm, salt, info, 42))
    }

    @Test
    fun `seal and open roundtrip`() {
        val key = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val nonce = LanCrypto.nonce(LanCrypto.DIR_CLIENT, 7)
        val plain = "кадр экрана".toByteArray()
        val sealed = LanCrypto.seal(key, nonce, 1, plain)
        assertFalse(plain.contentEquals(sealed))
        assertArrayEquals(plain, LanCrypto.open(key, nonce, 1, sealed))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val key = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val nonce = LanCrypto.nonce(LanCrypto.DIR_HOST, 1)
        val sealed = LanCrypto.seal(key, nonce, 1, "текст".toByteArray())
        sealed[0] = (sealed[0] + 1).toByte()
        assertNull(LanCrypto.open(key, nonce, 1, sealed))
    }

    @Test
    fun `frame type is authenticated`() {
        val key = LanCrypto.randomBytes(LanCrypto.KEY_BYTES)
        val nonce = LanCrypto.nonce(LanCrypto.DIR_HOST, 2)
        val sealed = LanCrypto.seal(key, nonce, 1, "текст".toByteArray())
        assertNull(LanCrypto.open(key, nonce, 2, sealed))
    }

    @Test
    fun `nonces differ by direction and counter`() {
        val first = LanCrypto.nonce(LanCrypto.DIR_CLIENT, 0)
        val second = LanCrypto.nonce(LanCrypto.DIR_CLIENT, 1)
        val host = LanCrypto.nonce(LanCrypto.DIR_HOST, 0)
        assertEquals(LanCrypto.NONCE_BYTES, first.size)
        assertNotEquals(first.toList(), second.toList())
        assertNotEquals(first.toList(), host.toList())
    }

    @Test
    fun `session keys depend on pin`() {
        val shared = LanCrypto.randomBytes(32)
        val salt = LanCrypto.randomBytes(16)
        val withPin = LanCrypto.deriveKeys(shared, salt, "123456")
        val otherPin = LanCrypto.deriveKeys(shared, salt, "654321")
        assertFalse(withPin.session.contentEquals(otherPin.session))
        assertFalse(withPin.auth.contentEquals(otherPin.auth))
        assertTrue(withPin.session.size == LanCrypto.KEY_BYTES)
    }
}
