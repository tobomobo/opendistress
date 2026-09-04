// SPDX-License-Identifier: MIT
package dev.opendistress.shared

import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

data class DirectConfigEnvelope(
    val watchId: String,
    val watchKeyVersion: Long,
    val configRevision: Long,
    val watchPublicKeyDigestSha256: ByteArray,
    val configDigestSha256: ByteArray,
    val wrappedAesKey: ByteArray,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    init {
        ProvisioningPaths.config(watchId)
        require(watchKeyVersion > 0 && configRevision > 0)
        require(watchPublicKeyDigestSha256.size == 32 && configDigestSha256.size == 32)
        require(wrappedAesKey.size in 256..1_024)
        require(nonce.size == 12)
        require(ciphertext.size in 17..8_192)
    }

    fun canonicalBytes(): ByteArray = encodeRecord(ENVELOPE_MAGIC) {
        writeString(watchId)
        writeLong(watchKeyVersion)
        writeLong(configRevision)
        writeBytes(watchPublicKeyDigestSha256)
        writeBytes(configDigestSha256)
        writeBytes(wrappedAesKey)
        writeBytes(nonce)
        writeBytes(ciphertext)
    }

    internal fun additionalAuthenticatedData(): ByteArray = encodeRecord(AAD_MAGIC) {
        writeString(watchId)
        writeLong(watchKeyVersion)
        writeLong(configRevision)
        writeBytes(watchPublicKeyDigestSha256)
        writeBytes(configDigestSha256)
    }

    override fun equals(other: Any?): Boolean = other is DirectConfigEnvelope &&
        watchId == other.watchId && watchKeyVersion == other.watchKeyVersion &&
        configRevision == other.configRevision &&
        watchPublicKeyDigestSha256.contentEquals(other.watchPublicKeyDigestSha256) &&
        configDigestSha256.contentEquals(other.configDigestSha256) &&
        wrappedAesKey.contentEquals(other.wrappedAesKey) && nonce.contentEquals(other.nonce) &&
        ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = canonicalBytes().contentHashCode()

    companion object {
        fun fromCanonicalBytes(bytes: ByteArray): DirectConfigEnvelope = decodeRecord(bytes, ENVELOPE_MAGIC) {
            DirectConfigEnvelope(
                watchId = readString(),
                watchKeyVersion = readLong(),
                configRevision = readLong(),
                watchPublicKeyDigestSha256 = readBytes(32),
                configDigestSha256 = readBytes(32),
                wrappedAesKey = readBytes(1_024),
                nonce = readBytes(12),
                ciphertext = readBytes(8_192),
            )
        }
    }
}

object ProvisioningCrypto {
    // AndroidKeyStore before API 35 supports OAEP SHA-256 with MGF1 SHA-1.
    private val OAEP_SHA256 = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA1,
        PSource.PSpecified.DEFAULT,
    )

    fun decodeRsaPublicKey(der: ByteArray): PublicKey =
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)).also {
            require(it is RSAPublicKey && it.modulus.bitLength() >= 2_048) {
                "watch provisioning key must be RSA-2048 or stronger"
            }
        }

    fun seal(
        config: DirectConfig,
        announcement: WatchKeyAnnouncement,
        random: SecureRandom = SecureRandom(),
    ): DirectConfigEnvelope {
        config.validate()
        val plaintext = config.canonicalBytes()
        val keyGenerator = KeyGenerator.getInstance("AES").apply { init(256, random) }
        val aesKey = keyGenerator.generateKey()
        val nonce = ByteArray(12).also(random::nextBytes)
        val keyDigest = announcement.publicKeyDigestSha256()
        val configDigest = config.digestSha256()
        val partial = DirectConfigEnvelope(
            watchId = announcement.watchId,
            watchKeyVersion = announcement.keyVersion,
            configRevision = config.revision,
            watchPublicKeyDigestSha256 = keyDigest,
            configDigestSha256 = configDigest,
            wrappedAesKey = ByteArray(256),
            nonce = nonce,
            ciphertext = ByteArray(17),
        )
        val aes = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, nonce), random)
            updateAAD(partial.additionalAuthenticatedData())
        }
        val ciphertext = aes.doFinal(plaintext)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(Cipher.ENCRYPT_MODE, decodeRsaPublicKey(announcement.publicKeyDer), OAEP_SHA256, random)
        }
        val wrapped = rsa.doFinal(aesKey.encoded)
        return partial.copy(wrappedAesKey = wrapped, ciphertext = ciphertext)
    }

    fun open(envelope: DirectConfigEnvelope, privateKey: PrivateKey): DirectConfig {
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(Cipher.DECRYPT_MODE, privateKey, OAEP_SHA256)
        }
        val rawKey = rsa.doFinal(envelope.wrappedAesKey)
        require(rawKey.size == 32) { "invalid wrapped configuration key" }
        val aes = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(128, envelope.nonce))
            updateAAD(envelope.additionalAuthenticatedData())
        }
        val plaintext = aes.doFinal(envelope.ciphertext)
        val config = DirectConfig.fromCanonicalBytes(plaintext)
        require(config.revision == envelope.configRevision) { "configuration revision mismatch" }
        require(MessageDigest.isEqual(config.digestSha256(), envelope.configDigestSha256)) {
            "configuration digest mismatch"
        }
        return config
    }

    fun open(
        envelope: DirectConfigEnvelope,
        privateKey: PrivateKey,
        expectedKey: WatchKeyAnnouncement,
    ): DirectConfig {
        require(envelope.watchId == expectedKey.watchId) { "watch id mismatch" }
        require(envelope.watchKeyVersion == expectedKey.keyVersion) { "watch key version mismatch" }
        require(
            MessageDigest.isEqual(
                envelope.watchPublicKeyDigestSha256,
                expectedKey.publicKeyDigestSha256(),
            ),
        ) { "watch public key mismatch" }
        return open(envelope, privateKey)
    }
}

private const val ENVELOPE_MAGIC = 0x4f444345 // ODCE
private const val AAD_MAGIC = 0x4f444144 // ODAD
