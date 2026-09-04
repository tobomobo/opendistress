// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import dev.opendistress.shared.DirectConfig
import dev.opendistress.shared.DirectConfigAck
import dev.opendistress.shared.ProvisioningPaths
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal data class PendingProvisioning(
    val watchId: String?,
    val revision: Long,
    val configDigestSha256: ByteArray,
) {
    init {
        watchId?.let(ProvisioningPaths::config)
        require(revision > 0 && configDigestSha256.size == 32)
    }

    override fun equals(other: Any?): Boolean = other is PendingProvisioning &&
        watchId == other.watchId && revision == other.revision &&
        configDigestSha256.contentEquals(other.configDigestSha256)

    override fun hashCode(): Int = 31 * (31 * (watchId?.hashCode() ?: 0) + revision.hashCode()) +
        configDigestSha256.contentHashCode()
}

internal data class ConfirmedProvisioning(
    val watchId: String,
    val revision: Long,
    val configDigestSha256: ByteArray,
    val acceptedAtEpochSeconds: Long,
) {
    init {
        ProvisioningPaths.ack(watchId)
        require(revision > 0 && configDigestSha256.size == 32 && acceptedAtEpochSeconds > 0)
    }

    override fun equals(other: Any?): Boolean = other is ConfirmedProvisioning &&
        watchId == other.watchId && revision == other.revision &&
        acceptedAtEpochSeconds == other.acceptedAtEpochSeconds &&
        configDigestSha256.contentEquals(other.configDigestSha256)

    override fun hashCode(): Int = 31 * (31 * (31 * watchId.hashCode() + revision.hashCode()) +
        configDigestSha256.contentHashCode()) + acceptedAtEpochSeconds.hashCode()
}

internal data class ProvisioningState(
    val config: DirectConfig? = null,
    val pending: PendingProvisioning? = null,
    val confirmed: ConfirmedProvisioning? = null,
    val drills: List<DrillEvidence> = emptyList(),
    val draft: SetupDraft? = null,
) {
    fun afterSave(next: DirectConfig): ProvisioningState = copy(
        config = next,
        pending = PendingProvisioning(null, next.revision, next.digestSha256()),
    )

    fun afterPublish(watchId: String): ProvisioningState {
        val current = requireNotNull(config)
        return copy(pending = PendingProvisioning(watchId, current.revision, current.digestSha256()))
    }

    fun afterAck(ack: DirectConfigAck): ProvisioningState {
        val expected = pending ?: return this
        if (expected.watchId != ack.watchId || expected.revision != ack.revision ||
            !MessageDigest.isEqual(expected.configDigestSha256, ack.configDigestSha256)
        ) return this
        return copy(
            pending = null,
            confirmed = ConfirmedProvisioning(
                ack.watchId,
                ack.revision,
                ack.configDigestSha256.clone(),
                ack.acceptedAtEpochSeconds,
            ),
        )
    }
}

internal object ProvisioningStateCodec {
    private const val MAGIC = 0x4f445053 // ODPS
    private const val VERSION = 3
    private const val MAX_BYTES = 32_768

    fun encode(state: ProvisioningState): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeOptionalBytes(state.config?.canonicalBytes())
            data.writeOptionalString(state.pending?.watchId)
            data.writeLong(state.pending?.revision ?: 0)
            data.writeOptionalBytes(state.pending?.configDigestSha256)
            data.writeOptionalString(state.confirmed?.watchId)
            data.writeLong(state.confirmed?.revision ?: 0)
            data.writeOptionalBytes(state.confirmed?.configDigestSha256)
            data.writeLong(state.confirmed?.acceptedAtEpochSeconds ?: 0)
            require(state.drills.size <= 4)
            data.writeInt(state.drills.size)
            state.drills.forEach {
                data.writeLong(it.revision)
                data.writeUTF(it.watch)
                data.writeUTF(it.provider)
                data.writeLong(it.recordedAt)
            }
            data.writeOptionalBytes(state.draft?.encode())
        }
        return output.toByteArray().also { require(it.size in 1..MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): ProvisioningState {
        require(bytes.size in 1..MAX_BYTES)
        val raw = ByteArrayInputStream(bytes)
        return DataInputStream(raw).use { data ->
            require(data.readInt() == MAGIC)
            val version = data.readInt()
            require(version in 1..VERSION)
            val config = data.readOptionalBytes()?.let(DirectConfig::fromCanonicalBytes)
            val pendingWatchId = data.readOptionalString()
            val pendingRevision = data.readLong()
            val pendingDigest = data.readOptionalBytes()
            val confirmedWatchId = data.readOptionalString()
            val confirmedRevision = data.readLong()
            val confirmedDigest = data.readOptionalBytes()
            val confirmedAt = data.readLong()
            val drills = if (version >= 2) {
                val count = data.readInt()
                require(count in 0..4)
                List(count) { DrillEvidence(data.readLong(), data.readUTF(), data.readUTF(), data.readLong()) }
            } else emptyList()
            val draft = if (version >= 3) data.readOptionalBytes()?.let(SetupDraft::decode) else null
            require(raw.available() == 0)
            ProvisioningState(
                config = config,
                drills = drills,
                draft = draft,
                pending = if (pendingRevision == 0L && pendingDigest == null && pendingWatchId == null) {
                    null
                } else {
                    require(pendingRevision > 0 && pendingDigest?.size == 32)
                    PendingProvisioning(pendingWatchId, pendingRevision, pendingDigest)
                },
                confirmed = if (confirmedRevision == 0L && confirmedDigest == null && confirmedWatchId == null && confirmedAt == 0L) {
                    null
                } else {
                    require(confirmedWatchId != null && confirmedRevision > 0 && confirmedDigest?.size == 32 && confirmedAt > 0)
                    ConfirmedProvisioning(confirmedWatchId, confirmedRevision, confirmedDigest, confirmedAt)
                },
            ).also { state ->
                state.pending?.let { require(config != null && it.revision == config.revision) }
            }
        }
    }

    private fun DataOutputStream.writeOptionalBytes(value: ByteArray?) {
        writeInt(value?.size ?: -1)
        value?.let(::write)
    }

    private fun DataInputStream.readOptionalBytes(): ByteArray? {
        val size = readInt()
        require(size == -1 || size in 0..8_192)
        return if (size == -1) null else ByteArray(size).also(::readFully)
    }

    private fun DataOutputStream.writeOptionalString(value: String?) {
        writeOptionalBytes(value?.toByteArray(Charsets.US_ASCII))
    }

    private fun DataInputStream.readOptionalString(): String? =
        readOptionalBytes()?.let { String(it, Charsets.US_ASCII) }
}
