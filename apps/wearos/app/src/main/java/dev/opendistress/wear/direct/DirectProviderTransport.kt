// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

internal data class DirectSendOutcome(
    val acceptance: DirectProviderAcceptance? = null,
    val retryable: Boolean,
    val reason: String,
)

internal class DirectProviderTransport {
    fun send(request: DirectHttpRequest): DirectSendOutcome {
        val body = request.body.toByteArray(StandardCharsets.UTF_8)
        if (body.size !in 1..MAX_REQUEST_BYTES) return DirectSendOutcome(retryable = false, reason = "invalid local request")
        val endpointValid = when (request.provider) {
            DirectProvider.GRAFANA ->
                DirectGrafanaAdapter.isWebhookUrl(request.endpoint) && request.contentType == "application/json"
            DirectProvider.PUSHOVER ->
                request.endpoint == DirectPushoverAdapter.ENDPOINT &&
                    request.contentType == "application/x-www-form-urlencoded; charset=utf-8"
        }
        if (!endpointValid) return DirectSendOutcome(retryable = false, reason = "invalid local endpoint")
        var connection: HttpsURLConnection? = null
        return try {
            val endpoint = URL(request.endpoint)
            val active = endpoint.openConnection() as? HttpsURLConnection
                ?: return DirectSendOutcome(retryable = true, reason = "HTTPS unavailable")
            connection = active
            active.instanceFollowRedirects = false
            active.requestMethod = "POST"
            active.connectTimeout = 10_000
            active.readTimeout = 15_000
            active.useCaches = false
            active.doOutput = true
            active.setFixedLengthStreamingMode(body.size)
            active.setRequestProperty("Content-Type", request.contentType)
            active.setRequestProperty("Accept", "application/json")
            active.setRequestProperty("Accept-Encoding", "identity")
            active.outputStream.use { it.write(body) }
            val status = active.responseCode
            val response = when {
                status in 200..299 -> active.inputStream?.use(::readBounded) ?: byteArrayOf()
                else -> active.errorStream?.use(::readBounded) ?: byteArrayOf()
            }
            classify(request.provider, status, response)
        } catch (_: Exception) {
            DirectSendOutcome(retryable = true, reason = "network result unknown")
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        fun classify(provider: DirectProvider, status: Int, response: ByteArray): DirectSendOutcome {
            val acceptance = when (provider) {
                DirectProvider.GRAFANA -> DirectGrafanaAdapter.acceptance(status)
                DirectProvider.PUSHOVER -> DirectPushoverAdapter.acceptance(status, response)
            }
            if (acceptance != null) {
                return DirectSendOutcome(acceptance, retryable = false, reason = "provider accepted")
            }
            val retryable = status == 408 || status == 425 || status == 429 || status >= 500 || status in 200..299
            return DirectSendOutcome(
                retryable = retryable,
                reason = if (retryable) "provider result unknown or retryable" else "provider rejected request",
            )
        }

        private const val MAX_REQUEST_BYTES = 16_384
        private const val MAX_RESPONSE_BYTES = 4_096

        private fun readBounded(input: java.io.InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                require(output.size() + count <= MAX_RESPONSE_BYTES) { "Provider response too large" }
                output.write(buffer, 0, count)
            }
        }
    }
}
