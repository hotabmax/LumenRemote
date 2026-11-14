package com.lumen.remote.net

import java.security.KeyPair

/**
 * Рукопожатие защищённого LAN-канала.
 *
 * Последовательность:
 * 1. клиент → `hello_crypto` (свой эфемерный ключ и nonce);
 * 2. хост → `crypto_ok` (свой ключ, nonce, соль и HMAC-подтверждение с PIN);
 * 3. клиент → `crypto_auth` (своё подтверждение);
 * 4. хост → `crypto_ready` (первый уже зашифрованный кадр).
 *
 * Сообщения описаны обычными картами строк, чтобы протокол проверялся
 * JVM-тестами без платформенных заглушек.
 */
object SecureHandshake {

    const val HELLO_CRYPTO = "hello_crypto"
    const val CRYPTO_OK = "crypto_ok"
    const val CRYPTO_AUTH = "crypto_auth"
    const val CRYPTO_READY = "crypto_ready"
    const val CRYPTO_FAIL = "crypto_fail"

    const val T = "t"

    private const val K_VERSION = "v"
    private const val K_PUBLIC = "kx"
    private const val K_NONCE = "nonce"
    private const val K_SALT = "salt"
    private const val K_PROOF = "proof"

    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 16

    /** Шаг клиента: сообщение и одноразовая пара ключей. */
    class ClientStart(
        val message: Map<String, String>,
        val keyPair: KeyPair,
        val nonce: ByteArray
    )

    /** Результат клиента: ключи сеанса и подтверждение для хоста. */
    class ClientConfirm(
        val keys: LanCrypto.SessionKeys,
        val message: Map<String, String>
    )

    /** Начальное сообщение клиента. */
    fun clientStart(): ClientStart {
        val keyPair = LanCrypto.newKeyPair()
        val nonce = LanCrypto.randomBytes(NONCE_BYTES)
        val message = mapOf(
            T to HELLO_CRYPTO,
            K_VERSION to LanCrypto.VERSION.toString(),
            K_PUBLIC to LanCrypto.encodePublicKey(keyPair.public),
            K_NONCE to Base64Codec.encode(nonce)
        )
        return ClientStart(message, keyPair, nonce)
    }

    /**
     * Шаг клиента после `crypto_ok`: проверяет подтверждение хоста и строит своё.
     *
     * @return null, если подтверждение не сошлось (другой PIN или подмена).
     */
    fun clientConfirm(
        start: ClientStart,
        ok: Map<String, String>,
        pin: String
    ): ClientConfirm? {
        val hostPublicEncoded = ok[K_PUBLIC] ?: return null
        val hostPublic = LanCrypto.decodePublicKey(hostPublicEncoded) ?: return null
        val hostNonce = decode(ok[K_NONCE]) ?: return null
        val salt = decode(ok[K_SALT]) ?: return null
        val hostProof = decode(ok[K_PROOF]) ?: return null

        val shared = LanCrypto.agree(start.keyPair.private, hostPublic)
        val keys = LanCrypto.deriveKeys(shared, salt, pin)
        val transcript = transcript(
            clientPublic = LanCrypto.encodePublicKey(start.keyPair.public),
            hostPublic = hostPublicEncoded,
            clientNonce = start.nonce,
            hostNonce = hostNonce,
            salt = salt
        )
        val expected = LanCrypto.hmac(keys.auth, "host".toByteArray() + transcript)
        if (!LanCrypto.constantTimeEquals(expected, hostProof)) return null

        val clientProof = LanCrypto.hmac(keys.auth, "client".toByteArray() + transcript)
        val message = mapOf(
            T to CRYPTO_AUTH,
            K_PROOF to Base64Codec.encode(clientProof)
        )
        return ClientConfirm(keys, message)
    }

    /**
     * Шаг хоста: проверяет `hello_crypto` и строит `crypto_ok`.
     *
     * @return ключи сеанса и сообщение для клиента; null — рукопожатие невозможно.
     */
    fun hostAccept(
        hello: Map<String, String>,
        pin: String
    ): Pair<LanCrypto.SessionKeys, Map<String, String>>? {
        val clientPublicEncoded = hello[K_PUBLIC] ?: return null
        val clientPublic = LanCrypto.decodePublicKey(clientPublicEncoded) ?: return null
        val clientNonce = decode(hello[K_NONCE]) ?: return null

        val keyPair = LanCrypto.newKeyPair()
        val hostNonce = LanCrypto.randomBytes(NONCE_BYTES)
        val salt = LanCrypto.randomBytes(SALT_BYTES)
        val shared = LanCrypto.agree(keyPair.private, clientPublic)
        val keys = LanCrypto.deriveKeys(shared, salt, pin)
        val hostPublicEncoded = LanCrypto.encodePublicKey(keyPair.public)
        val transcript = transcript(
            clientPublic = clientPublicEncoded,
            hostPublic = hostPublicEncoded,
            clientNonce = clientNonce,
            hostNonce = hostNonce,
            salt = salt
        )
        val proof = LanCrypto.hmac(keys.auth, "host".toByteArray() + transcript)
        val message = mapOf(
            T to CRYPTO_OK,
            K_VERSION to LanCrypto.VERSION.toString(),
            K_PUBLIC to hostPublicEncoded,
            K_NONCE to Base64Codec.encode(hostNonce),
            K_SALT to Base64Codec.encode(salt),
            K_PROOF to Base64Codec.encode(proof)
        )
        return keys to message
    }

    /** Проверка подтверждения клиента на стороне хоста. */
    fun hostVerify(
        auth: Map<String, String>,
        hello: Map<String, String>,
        okMessage: Map<String, String>,
        keys: LanCrypto.SessionKeys
    ): Boolean {
        val clientProof = decode(auth[K_PROOF]) ?: return false
        val clientNonce = decode(hello[K_NONCE]) ?: return false
        val hostNonce = decode(okMessage[K_NONCE]) ?: return false
        val salt = decode(okMessage[K_SALT]) ?: return false
        val transcript = transcript(
            clientPublic = hello[K_PUBLIC] ?: return false,
            hostPublic = okMessage[K_PUBLIC] ?: return false,
            clientNonce = clientNonce,
            hostNonce = hostNonce,
            salt = salt
        )
        val expected = LanCrypto.hmac(keys.auth, "client".toByteArray() + transcript)
        return LanCrypto.constantTimeEquals(expected, clientProof)
    }

    /** Общая расшифровка рукопожатия — одинаковая у обеих сторон. */
    private fun transcript(
        clientPublic: String,
        hostPublic: String,
        clientNonce: ByteArray,
        hostNonce: ByteArray,
        salt: ByteArray
    ): ByteArray {
        val version = byteArrayOf(LanCrypto.VERSION.toByte())
        return version + clientPublic.toByteArray() + hostPublic.toByteArray() +
            clientNonce + hostNonce + salt
    }

    private fun decode(text: String?): ByteArray? {
        if (text.isNullOrBlank()) return null
        return runCatching { Base64Codec.decode(text) }.getOrNull()
    }
}
