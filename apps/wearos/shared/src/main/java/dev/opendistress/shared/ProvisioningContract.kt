// SPDX-License-Identifier: MIT
package dev.opendistress.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ProvisioningPaths {
    const val PREFIX = "/opendistress/provisioning/v1"
    const val WATCH_KEY_PREFIX = "$PREFIX/watch-key/"
    const val KEY_REQUEST = "$PREFIX/watch-key-request"
    const val CONFIG_PREFIX = "$PREFIX/direct-config/"
    const val ACK_PREFIX = "$PREFIX/direct-config-ack/"
    const val WATCH_CAPABILITY = "opendistress_direct_config_v1"

    fun watchKey(watchId: String): String = WATCH_KEY_PREFIX + checkedWatchId(watchId)
    fun config(watchId: String): String = CONFIG_PREFIX + checkedWatchId(watchId)
    fun ack(watchId: String): String = ACK_PREFIX + checkedWatchId(watchId)

    fun watchIdFrom(path: String, prefix: String): String? =
        path.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf { runCatching { checkedWatchId(it) }.isSuccess }

    private fun checkedWatchId(value: String): String {
        require(value.length in 16..64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "invalid watch id"
        }
        return value
    }
}

object ProvisioningDataKeys {
    const val PAYLOAD = "payload"
    const val REVISION = "revision"
    const val DIGEST_SHA256 = "digest_sha256"
}

data class WatchKeyAnnouncement(
    val watchId: String,
    val keyVersion: Long,
    val publicKeyDer: ByteArray,
) {
    init {
        ProvisioningPaths.watchKey(watchId)
        require(keyVersion > 0)
        require(publicKeyDer.size in 256..1_024)
    }

    fun canonicalBytes(): ByteArray = encodeRecord(KEY_MAGIC) {
        writeString(watchId)
        writeLong(keyVersion)
        writeBytes(publicKeyDer)
    }

    fun publicKeyDigestSha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(publicKeyDer)

    override fun equals(other: Any?): Boolean = other is WatchKeyAnnouncement &&
        watchId == other.watchId && keyVersion == other.keyVersion && publicKeyDer.contentEquals(other.publicKeyDer)

    override fun hashCode(): Int = 31 * (31 * watchId.hashCode() + keyVersion.hashCode()) + publicKeyDer.contentHashCode()

    companion object {
        fun fromCanonicalBytes(bytes: ByteArray): WatchKeyAnnouncement = decodeRecord(bytes, KEY_MAGIC) {
            WatchKeyAnnouncement(readString(), readLong(), readBytes(1_024))
        }
    }
}

data class WatchKeyRequest(
    val nonce: ByteArray,
    val createdAtEpochMillis: Long,
) {
    init {
        require(nonce.size == 16)
        require(createdAtEpochMillis > 0)
    }

    fun canonicalBytes(): ByteArray = encodeRecord(KEY_REQUEST_MAGIC) {
        writeBytes(nonce)
        writeLong(createdAtEpochMillis)
    }

    override fun equals(other: Any?): Boolean = other is WatchKeyRequest &&
        createdAtEpochMillis == other.createdAtEpochMillis && nonce.contentEquals(other.nonce)

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + createdAtEpochMillis.hashCode()

    companion object {
        fun fromCanonicalBytes(bytes: ByteArray): WatchKeyRequest = decodeRecord(bytes, KEY_REQUEST_MAGIC) {
            WatchKeyRequest(readBytes(16), readLong())
        }
    }
}

data class DirectConfigAck(
    val watchId: String,
    val revision: Long,
    val configDigestSha256: ByteArray,
    val acceptedAtEpochSeconds: Long,
) {
    init {
        ProvisioningPaths.ack(watchId)
        require(revision > 0)
        require(configDigestSha256.size == 32)
        require(acceptedAtEpochSeconds > 0)
    }

    fun canonicalBytes(): ByteArray = encodeRecord(ACK_MAGIC) {
        writeString(watchId)
        writeLong(revision)
        writeBytes(configDigestSha256)
        writeLong(acceptedAtEpochSeconds)
    }

    override fun equals(other: Any?): Boolean = other is DirectConfigAck &&
        watchId == other.watchId && revision == other.revision &&
        acceptedAtEpochSeconds == other.acceptedAtEpochSeconds &&
        configDigestSha256.contentEquals(other.configDigestSha256)

    override fun hashCode(): Int = 31 * (31 * (31 * watchId.hashCode() + revision.hashCode()) + configDigestSha256.contentHashCode()) + acceptedAtEpochSeconds.hashCode()

    companion object {
        fun fromCanonicalBytes(bytes: ByteArray): DirectConfigAck = decodeRecord(bytes, ACK_MAGIC) {
            DirectConfigAck(readString(), readLong(), readBytes(32), readLong())
        }
    }
}

internal const val KEY_MAGIC = 0x4f44574b // ODWK
internal const val KEY_REQUEST_MAGIC = 0x4f444b52 // ODKR
internal const val ACK_MAGIC = 0x4f444143 // ODAC
internal const val RECORD_VERSION = 1
internal const val MAX_RECORD_BYTES = 16_384

internal fun encodeRecord(magic: Int, block: DataOutputStream.() -> Unit): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use {
        it.writeInt(magic)
        it.writeInt(RECORD_VERSION)
        it.block()
    }
    return output.toByteArray().also { require(it.size <= MAX_RECORD_BYTES) }
}

internal fun <T> decodeRecord(bytes: ByteArray, magic: Int, block: DataInputStream.() -> T): T {
    require(bytes.size in 1..MAX_RECORD_BYTES)
    val input = ByteArrayInputStream(bytes)
    return DataInputStream(input).use {
        require(it.readInt() == magic) { "invalid provisioning record" }
        require(it.readInt() == RECORD_VERSION) { "unsupported provisioning version" }
        it.block().also { require(input.available() == 0) { "trailing provisioning data" } }
    }
}

internal fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.US_ASCII)
    require(bytes.size in 1..256 && bytes.all { it.toInt() in 0x21..0x7e })
    writeInt(bytes.size)
    write(bytes)
}

internal fun DataInputStream.readString(): String {
    val bytes = readBytes(256)
    require(bytes.isNotEmpty() && bytes.all { it.toInt() in 0x21..0x7e })
    return String(bytes, StandardCharsets.US_ASCII)
}

internal fun DataOutputStream.writeBytes(value: ByteArray) {
    writeInt(value.size)
    write(value)
}

internal fun DataInputStream.readBytes(maximum: Int): ByteArray {
    val length = readInt()
    require(length in 1..maximum) { "invalid byte string length" }
    return ByteArray(length).also(::readFully)
}
