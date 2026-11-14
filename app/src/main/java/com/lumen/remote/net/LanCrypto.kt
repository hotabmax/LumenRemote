package com.lumen.remote.net

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Криптопримитивы защищённого LAN-канала.
 *
 * Схема: одноразовый ECDH (P-256) даёт общий секрет, из него HKDF-SHA256 выводит
 * ключ сеанса и ключ подтверждения. Подтверждение — HMAC с примесью PIN-кода:
 * пассивный наблюдатель расшифровать трафик не может, а посреднику, подменяющему
 * ключи, нужно угадать PIN, иначе подтверждение не сойдётся.
 *
 * Известное ограничение: это не полноценный PAKE. Активная MITM-атака на этапе
 * рукопожатия допускает офлайн-перебор 6-значного PIN.
 */
object LanCrypto {

    /** Версия протокола рукопожатия. */
    const val VERSION = 1

    /** Длина ключа AES-256 в байтах. */
    const val KEY_BYTES = 32

    /** Размер nonce для AES-GCM. */
    const val NONCE_BYTES = 12

    /** Направление кадров: их нельзя переставлять между потоками. */
    const val DIR_CLIENT = 1.toByte()
    const val DIR_HOST = 2.toByte()

    private const val CURVE = "secp256r1"
    private const val KEY_ALGORITHM = "EC"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val AEAD = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    /** Пара ключей сеанса: кадры и подтверждение рукопожатия. */
    class SessionKeys(val session: ByteArray, val auth: ByteArray)

    /** Одноразовая пара ключей для ECDH. */
    fun newKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        generator.initialize(ECGenParameterSpec(CURVE))
        return generator.generateKeyPair()
    }

    /** Открытый ключ в пригодной для отправки форме. */
    fun encodePublicKey(key: PublicKey): String =
        Base64Codec.encode(key.encoded)

    /** Разбирает открытый ключ партнёра; null — ключ некорректный. */
    fun decodePublicKey(encoded: String): PublicKey? = runCatching {
        val bytes = Base64Codec.decode(encoded)
        KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(bytes))
    }.getOrNull()

    /** Общий секрет ECDH. */
    fun agree(privateKey: PrivateKey, peerPublic: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublic, true)
        return agreement.generateSecret()
    }

    /** Случайные байты для соли и одноразовых значений. */
    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    /** HKDF-SHA256 (extract + expand) по RFC 5869. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(salt, HMAC_ALGORITHM))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val chunk = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, chunk)
            offset += chunk
            counter += 1
        }
        return out
    }

    /** HMAC-SHA256. */
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    /** Сравнение подтверждений без утечки по времени. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /**
     * Ключи сеанса из общего секрета ECDH и PIN-кода.
     *
     * PIN никуда не передаётся: он примешивается к производным ключам, поэтому
     * без него подтверждение партнёра проверить нельзя.
     */
    fun deriveKeys(sharedSecret: ByteArray, salt: ByteArray, pin: String): SessionKeys {
        val session = hkdf(
            sharedSecret,
            salt,
            ("lumen-lan-v1/session:" + pin).toByteArray(),
            KEY_BYTES
        )
        val auth = hkdf(
            sharedSecret,
            salt,
            ("lumen-lan-v1/auth:" + pin).toByteArray(),
            KEY_BYTES
        )
        return SessionKeys(session, auth)
    }

    /** Шифрует полезную нагрузку кадра; тип кадра идёт как AAD. */
    fun seal(key: ByteArray, nonce: ByteArray, type: Int, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AEAD)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.updateAAD(byteArrayOf(type.toByte()))
        return cipher.doFinal(plain)
    }

    /** Расшифровывает payload кадра; null — повреждён или подменён. */
    fun open(key: ByteArray, nonce: ByteArray, type: Int, sealed: ByteArray): ByteArray? =
        runCatching {
            val cipher = Cipher.getInstance(AEAD)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            cipher.updateAAD(byteArrayOf(type.toByte()))
            cipher.doFinal(sealed)
        }.getOrNull()

    /** Nonce кадра: байт направления + счётчик длиной [NONCE_BYTES] - 1. */
    fun nonce(direction: Byte, counter: Long): ByteArray {
        val out = ByteArray(NONCE_BYTES)
        out[0] = direction
        var value = counter
        for (index in NONCE_BYTES - 1 downTo 1) {
            out[index] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        return out
    }
}
