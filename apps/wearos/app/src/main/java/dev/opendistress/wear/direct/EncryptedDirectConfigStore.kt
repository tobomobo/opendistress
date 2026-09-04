// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import android.content.Context
import android.util.AtomicFile
import dev.opendistress.shared.DirectConfig
import dev.opendistress.shared.DirectConfigAck
import dev.opendistress.shared.DirectConfigEnvelope
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream

internal data class InstalledDirectConfig(
    val config: DirectConfig,
    val ack: DirectConfigAck,
)

internal class EncryptedDirectConfigStore(
    context: Context,
    private val identity: WatchConfigIdentity = AndroidKeystoreWatchIdentity(),
) {
    private val directory = context.filesDir.resolve("direct-test")
    private val atomic = AtomicFile(directory.resolve("config-envelope.bin"))
    private var installed: InstalledDirectConfig?

    init {
        require(directory.exists() || directory.mkdirs()) { "Cannot create direct-config store" }
        installed = readStored()
    }

    @Synchronized
    fun watchAnnouncement() = identity.announcement()

    @Synchronized
    fun snapshot(): InstalledDirectConfig? = installed

    @Synchronized
    fun install(encodedEnvelope: ByteArray, acceptedAt: Long = System.currentTimeMillis() / 1_000): DirectConfigAck {
        require(acceptedAt > 0)
        val envelope = DirectConfigEnvelope.fromCanonicalBytes(encodedEnvelope)
        val config = identity.open(envelope)
        val candidate = InstalledDirectConfig(
            config,
            DirectConfigAck(
                envelope.watchId,
                config.revision,
                config.digestSha256(),
                acceptedAt,
            ),
        )
        val current = installed
        if (current != null) {
            require(candidate.ack.revision >= current.ack.revision) { "Direct-config rollback rejected" }
            if (candidate.ack.revision == current.ack.revision) {
                require(candidate.ack.configDigestSha256.contentEquals(current.ack.configDigestSha256)) {
                    "Direct-config revision collision rejected"
                }
                return current.ack
            }
        }
        write(encodedEnvelope)
        installed = candidate
        return candidate.ack
    }

    private fun readStored(): InstalledDirectConfig? {
        val encoded = try {
            atomic.openRead().use(::readBounded)
        } catch (_: FileNotFoundException) {
            return null
        }
        val envelope = DirectConfigEnvelope.fromCanonicalBytes(encoded)
        val config = identity.open(envelope)
        return InstalledDirectConfig(
            config,
            DirectConfigAck(
                envelope.watchId,
                config.revision,
                config.digestSha256(),
                System.currentTimeMillis() / 1_000,
            ),
        )
    }

    private fun write(encodedEnvelope: ByteArray) {
        var output: FileOutputStream? = null
        try {
            val stream = atomic.startWrite()
            output = stream
            stream.write(encodedEnvelope)
            stream.flush()
            atomic.finishWrite(stream)
            output = null
        } catch (error: Exception) {
            output?.let(atomic::failWrite)
            throw error
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1_024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray().also {
                require(it.isNotEmpty()) { "Stored direct config is empty" }
            }
            require(output.size() + count <= MAX_ENVELOPE_BYTES) { "Stored direct config is too large" }
            output.write(buffer, 0, count)
        }
    }

    private companion object {
        const val MAX_ENVELOPE_BYTES = 16_384
    }
}
