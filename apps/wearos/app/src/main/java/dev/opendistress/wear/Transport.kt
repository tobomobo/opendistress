// SPDX-License-Identifier: MIT
package dev.opendistress.wear

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class SendOutcome(
    val accepted: Boolean,
    val label: String,
)

internal data class StatusPollOutcome(
    val verified: VerifiedIncidentStatus?,
)

internal class Transport(private val config: RuntimeConfig) {
    private val statusEndpoint = URL(
        config.endpoint.protocol,
        config.endpoint.host,
        config.endpoint.port,
        "/v2/status",
    )

    fun send(event: IncidentEvent): SendOutcome {
        val body = event.wireJson().toByteArray(StandardCharsets.US_ASCII)
        if (body.size !in 1..1024) return pending("invalid local event size")
        var connection: HttpsURLConnection? = null
        return try {
            val active = config.endpoint.openConnection() as? HttpsURLConnection
                ?: return pending("HTTPS unavailable")
            connection = active
            active.instanceFollowRedirects = false
            active.requestMethod = "POST"
            active.connectTimeout = 10_000
            active.readTimeout = 15_000
            active.useCaches = false
            active.doOutput = true
            active.setFixedLengthStreamingMode(body.size)
            active.setRequestProperty("Content-Type", "application/json")
            active.setRequestProperty("Accept", "application/json")
            active.setRequestProperty("Accept-Encoding", "identity")
            active.setRequestProperty("X-OpenDistress-Signature", event.requestSignature)
            active.outputStream.use { it.write(body) }

            if (active.responseCode != 202) {
                return pending("relay did not durably accept")
            }
            val mediaType = active.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            if (mediaType != "application/json") {
                return pending("relay response type was invalid")
            }
            val response = active.inputStream.use(::readBounded)
            if (!Protocol.verifyAcceptedResponse(response, event, config.authKey)) {
                pending("relay evidence was invalid")
            } else {
                SendOutcome(
                    accepted = true,
                    label = "Relay durably accepted ${event.kind}; delivery not confirmed",
                )
            }
        } catch (_: Exception) {
            pending("network unavailable")
        } finally {
            connection?.disconnect()
        }
    }

    fun sendStatus(query: StatusQuery): StatusPollOutcome {
        val body = query.wireJson().toByteArray(StandardCharsets.US_ASCII)
        if (body.size !in 1..1024) return StatusPollOutcome(null)
        var connection: HttpsURLConnection? = null
        return try {
            val active = statusEndpoint.openConnection() as? HttpsURLConnection
                ?: return StatusPollOutcome(null)
            connection = active
            active.instanceFollowRedirects = false
            active.requestMethod = "POST"
            active.connectTimeout = 10_000
            active.readTimeout = 15_000
            active.useCaches = false
            active.doOutput = true
            active.setFixedLengthStreamingMode(body.size)
            active.setRequestProperty("Content-Type", "application/json")
            active.setRequestProperty("Accept", "application/json")
            active.setRequestProperty("Accept-Encoding", "identity")
            active.setRequestProperty("X-OpenDistress-Signature", query.requestSignature)
            active.outputStream.use { it.write(body) }

            if (active.responseCode != 200) return StatusPollOutcome(null)
            val mediaType = active.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            if (mediaType != "application/json") return StatusPollOutcome(null)
            val response = active.inputStream.use(::readBounded)
            StatusPollOutcome(
                Protocol.verifyStatusResponse(
                    response,
                    query,
                    config.authKey,
                    System.currentTimeMillis() / 1000,
                ),
            )
        } catch (_: Exception) {
            StatusPollOutcome(null)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > MAX_RESPONSE_BYTES) {
                throw IllegalArgumentException("Relay response is too large")
            }
            output.write(buffer, 0, count)
        }
    }

    private fun pending(reason: String): SendOutcome = SendOutcome(
        accepted = false,
        label = "Stored on watch — relay acceptance pending ($reason)",
    )

    private companion object {
        const val MAX_RESPONSE_BYTES = 512
    }
}
