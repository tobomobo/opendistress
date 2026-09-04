// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import dev.opendistress.shared.DirectConfig
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class DirectProvider { GRAFANA, PUSHOVER }
internal enum class DirectRequestKind { TRIGGER, LOCATION, CANCEL }

internal data class DirectHttpRequest(
    val requestId: String,
    val incidentId: String,
    val provider: DirectProvider,
    val configurationFingerprint: String,
    val endpoint: String,
    val contentType: String,
    val body: String,
    val kind: DirectRequestKind,
    val sequence: Long,
    val createdAt: Long,
    val expiresAt: Long,
)

internal data class DirectProviderAcceptance(
    val provider: DirectProvider,
    val reference: String,
    val emergencyReceipt: String? = null,
)

internal data class DirectProfile(
    val personName: String,
    val alertMessage: String,
    val homeAddress: String,
    val childrenInfo: String,
    val personDescription: String,
    val backgroundInfo: String,
    val responseInstructions: String,
    val photoUrl: String,
) {
    companion object {
        fun from(config: DirectConfig): DirectProfile = DirectProfile(
            personName = bounded(config.protectedPersonName, 40),
            alertMessage = bounded(config.customAlertMessage, 240),
            homeAddress = bounded(config.homeAddress, 120),
            childrenInfo = bounded(config.childrenInfo, 150),
            personDescription = bounded(config.personDescription, 150),
            backgroundInfo = bounded(config.backgroundInfo, 180),
            responseInstructions = bounded(config.responseInstructions, 180),
            photoUrl = validPhotoUrl(config.profilePhotoUrl),
        )

        private fun bounded(value: String?, max: Int): String =
            value?.takeIf { it.length in 1..max } ?: ""

        private fun validPhotoUrl(value: String?): String {
            val candidate = bounded(value, 512)
            val uri = try {
                URI(candidate)
            } catch (_: Exception) {
                return ""
            }
            return candidate.takeIf {
                uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null
            } ?: ""
        }
    }
}

internal object DirectProviderFingerprint {
    fun grafana(webhookUrl: String): String {
        require(DirectGrafanaAdapter.isWebhookUrl(webhookUrl))
        return hmacFingerprint(
            "opendistress.direct.grafana.config.v1",
            webhookUrl,
            "webhook=$webhookUrl\n",
        )
    }

    fun pushover(userKey: String, apiToken: String): String {
        require(DirectPushoverAdapter.isToken(userKey) && DirectPushoverAdapter.isToken(apiToken))
        return hmacFingerprint(
            "opendistress.direct.pushover.config.v1",
            apiToken,
            "user=$userKey\ntoken=$apiToken\n",
        )
    }

    private fun hmacFingerprint(domain: String, key: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$domain\n$value".toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}

internal object DirectAlertText {
    const val TEST_TITLE = "TESTNOTRUF — OPENDISTRESS"
    const val TEST_MESSAGE =
        "KEIN ECHTER NOTFALL. NUR UEBUNG: keine Polizei verstaendigen. OpenDistress Testausloesung."

    fun personalizedTitle(base: String, profile: DirectProfile): String =
        if (profile.personName.isEmpty()) base else "$base — ${profile.personName}"

    fun initialMessage(profile: DirectProfile): String =
        section(section(TEST_MESSAGE, "REAKTIONSPLAN (NUR UEBUNG)", profile.responseInstructions),
            "VORBEREITETE NACHRICHT", profile.alertMessage)

    fun pushoverMessage(profile: DirectProfile): String {
        // The full response plan can contain expected callback words: never cut it off.
        var message = section(TEST_MESSAGE, "REAKTIONSPLAN (NUR UEBUNG)", profile.responseInstructions)
        message = section(message, "VORBEREITETE NACHRICHT", clipped(profile.alertMessage, 160))
        message = section(message, "GESCHUETZTE PERSON", clipped(profile.personName, 40))
        message = section(message, "PERSONENBESCHREIBUNG", clipped(profile.personDescription, 100))
        message = section(message, "KINDER / FAMILIE", clipped(profile.childrenInfo, 100))
        message = section(message, "HEIMADRESSE (NICHT GPS)", clipped(profile.homeAddress, 100))
        message = section(message, "HINTERGRUND", clipped(profile.backgroundInfo, 90))
        return if (message.length <= 1_024) message else TEST_MESSAGE
    }

    private fun section(base: String, title: String, value: String): String =
        if (value.isEmpty()) base else "$base\n\n$title\n$value"

    private fun clipped(value: String, max: Int): String = when {
        value.length <= max -> value
        max <= 3 -> value.take(max)
        else -> value.take(max - 3) + "..."
    }
}

internal object DirectGrafanaAdapter {
    fun isWebhookUrl(value: String?): Boolean {
        val uri = try {
            URI(value ?: return false)
        } catch (_: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        val marker = "/integrations/v1/formatted_webhook/"
        val path = uri.rawPath ?: return false
        val token = path.substringAfter(marker, "").removeSuffix("/")
        return uri.scheme == "https" &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port in listOf(-1, 443) &&
            host.endsWith(".grafana.net") && host != "grafana.net" &&
            path.contains(marker) && path.endsWith("/") && token.isNotEmpty() && !token.contains('/')
    }

    fun trigger(config: DirectConfig, incidentId: String, now: Long, expiresAt: Long): DirectHttpRequest {
        val endpoint = requireNotNull(config.grafanaWebhookUrl)
        require(isWebhookUrl(endpoint))
        val profile = DirectProfile.from(config)
        return request(
            incidentId,
            now,
            expiresAt,
            0,
            DirectRequestKind.TRIGGER,
            endpoint,
            grafanaPayload(
                incidentId,
                DirectAlertText.personalizedTitle(DirectAlertText.TEST_TITLE, profile),
                DirectAlertText.initialMessage(profile),
                profile,
                emptyMap(),
            ),
        )
    }

    fun location(
        config: DirectConfig,
        incidentId: String,
        now: Long,
        expiresAt: Long,
        update: FormattedLocationUpdate,
    ): DirectHttpRequest {
        val endpoint = requireNotNull(config.grafanaWebhookUrl)
        require(isWebhookUrl(endpoint))
        val profile = DirectProfile.from(config)
        return request(
            incidentId,
            now,
            expiresAt,
            update.sequence,
            DirectRequestKind.LOCATION,
            endpoint,
            grafanaPayload(
                incidentId,
                DirectAlertText.personalizedTitle(update.title, profile),
                update.message,
                profile,
                update.grafanaFields,
            ),
        )
    }

    fun acceptance(statusCode: Int): DirectProviderAcceptance? =
        if (statusCode in 200..299) {
            DirectProviderAcceptance(DirectProvider.GRAFANA, "http_$statusCode")
        } else null

    private fun request(
        incidentId: String,
        now: Long,
        expiresAt: Long,
        sequence: Long,
        kind: DirectRequestKind,
        endpoint: String,
        body: String,
    ) = DirectHttpRequest(
        requestId = randomDirectId(),
        incidentId = incidentId,
        provider = DirectProvider.GRAFANA,
        configurationFingerprint = DirectProviderFingerprint.grafana(endpoint),
        endpoint = endpoint,
        contentType = "application/json",
        body = body,
        kind = kind,
        sequence = sequence,
        createdAt = now,
        expiresAt = expiresAt,
    )

    private fun grafanaPayload(
        incidentId: String,
        title: String,
        message: String,
        profile: DirectProfile,
        extra: Map<String, Any>,
    ): String {
        val values = linkedMapOf<String, Any>(
            "alert_uid" to incidentId,
            "title" to title,
            "state" to "alerting",
            "message" to message,
            "alert_message" to profile.alertMessage,
            "person_name" to profile.personName,
            "home_address" to profile.homeAddress,
            "children_info" to profile.childrenInfo,
            "person_description" to profile.personDescription,
            "background_info" to profile.backgroundInfo,
            "response_instructions" to profile.responseInstructions,
            "profile_photo_url" to profile.photoUrl,
        )
        values.putAll(extra)
        return values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${jsonString(key)}:${jsonValue(value)}"
        }
    }
}

internal object DirectPushoverAdapter {
    const val ENDPOINT = "https://api.pushover.net/1/messages.json"
    private const val RECEIPT_ENDPOINT_PREFIX = "https://api.pushover.net/1/receipts/"
    private val TOKEN = Regex("^[A-Za-z0-9]{30}$")

    fun isToken(value: String?): Boolean = value != null && TOKEN.matches(value)

    fun trigger(config: DirectConfig, incidentId: String, now: Long, expiresAt: Long): DirectHttpRequest {
        val user = requireNotNull(config.pushoverUserKey)
        val token = requireNotNull(config.pushoverApiToken)
        require(isToken(user) && isToken(token) && now < expiresAt)
        val profile = DirectProfile.from(config)
        val values = linkedMapOf(
            "token" to token,
            "user" to user,
            "title" to DirectAlertText.personalizedTitle(DirectAlertText.TEST_TITLE, profile),
            "message" to DirectAlertText.pushoverMessage(profile),
            "priority" to "2",
            "retry" to "30",
            "expire" to (expiresAt - now).toString(),
        )
        if (profile.photoUrl.isNotEmpty()) {
            values["url"] = profile.photoUrl
            values["url_title"] = "Open profile photo"
        }
        return request(user, token, incidentId, now, expiresAt, 0, DirectRequestKind.TRIGGER, values)
    }

    fun location(
        config: DirectConfig,
        incidentId: String,
        now: Long,
        expiresAt: Long,
        update: FormattedLocationUpdate,
    ): DirectHttpRequest {
        val user = requireNotNull(config.pushoverUserKey)
        val token = requireNotNull(config.pushoverApiToken)
        require(isToken(user) && isToken(token) && now < expiresAt)
        val values = linkedMapOf(
            "token" to token,
            "user" to user,
            "title" to DirectAlertText.personalizedTitle(update.title, DirectProfile.from(config)),
            "message" to update.message,
            "priority" to if (update.sequence == 1L) "1" else "0",
            "timestamp" to now.toString(),
            "url" to update.mapUrl,
            "url_title" to if (update.mayBeStale) "Open possibly stale location" else "Open current location",
        )
        return request(user, token, incidentId, now, expiresAt, update.sequence, DirectRequestKind.LOCATION, values)
    }

    fun cancel(
        config: DirectConfig,
        incidentId: String,
        receipt: String,
        now: Long,
        expiresAt: Long,
    ): DirectHttpRequest {
        val user = requireNotNull(config.pushoverUserKey)
        val token = requireNotNull(config.pushoverApiToken)
        require(isToken(user) && isToken(token) && isToken(receipt) && now < expiresAt)
        return request(
            user,
            token,
            incidentId,
            now,
            expiresAt,
            0,
            DirectRequestKind.CANCEL,
            mapOf("token" to token),
            cancellationEndpoint(receipt),
        )
    }

    fun isCancellationEndpoint(value: String): Boolean {
        if (!value.startsWith(RECEIPT_ENDPOINT_PREFIX) || !value.endsWith("/cancel.json")) return false
        val receipt = value.removePrefix(RECEIPT_ENDPOINT_PREFIX).removeSuffix("/cancel.json")
        return isToken(receipt)
    }

    fun acceptance(
        statusCode: Int,
        body: ByteArray,
        kind: DirectRequestKind,
    ): DirectProviderAcceptance? {
        if (statusCode != 200 || body.size !in 1..4_096) return null
        val fields = try {
            FlatProviderJsonParser(body).parse()
        } catch (_: Exception) {
            return null
        }
        val expectedFields = if (kind == DirectRequestKind.TRIGGER) {
            setOf("status", "request", "receipt")
        } else {
            setOf("status", "request")
        }
        if (fields.keys != expectedFields) return null
        if (fields["status"] != ProviderJsonValue("1", false)) return null
        val request = fields["request"]?.takeIf { it.quoted }?.text ?: return null
        if (!isRequestReference(request)) return null
        val receipt = fields["receipt"]?.takeIf { it.quoted }?.text
        if (kind == DirectRequestKind.TRIGGER && !isToken(receipt)) return null
        return DirectProviderAcceptance(DirectProvider.PUSHOVER, request, receipt)
    }

    fun isRequestReference(value: String?): Boolean =
        value != null && value.length in 1..128 && value.all { it.code in 0x21..0x7e }

    fun isDefiniteRejection(statusCode: Int, body: ByteArray): Boolean {
        if (statusCode != 200 || body.size !in 1..4_096) return false
        val fields = runCatching { FlatProviderJsonParser(body).parse() }.getOrNull() ?: return false
        return fields["status"] == ProviderJsonValue("0", false)
    }

    private fun request(
        user: String,
        token: String,
        incidentId: String,
        now: Long,
        expiresAt: Long,
        sequence: Long,
        kind: DirectRequestKind,
        values: Map<String, String>,
        endpoint: String = ENDPOINT,
    ) = DirectHttpRequest(
        requestId = randomDirectId(),
        incidentId = incidentId,
        provider = DirectProvider.PUSHOVER,
        configurationFingerprint = DirectProviderFingerprint.pushover(user, token),
        endpoint = endpoint,
        contentType = "application/x-www-form-urlencoded; charset=utf-8",
        body = values.entries.joinToString("&") { (key, value) -> "${form(key)}=${form(value)}" },
        kind = kind,
        sequence = sequence,
        createdAt = now,
        expiresAt = expiresAt,
    )

    private fun cancellationEndpoint(receipt: String): String =
        "$RECEIPT_ENDPOINT_PREFIX$receipt/cancel.json"
}

private fun randomDirectId(): String {
    val bytes = ByteArray(16)
    java.security.SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun form(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

private fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}

private fun jsonValue(value: Any): String = when (value) {
    is String -> jsonString(value)
    is Boolean, is Int, is Long -> value.toString()
    else -> throw IllegalArgumentException("Unsupported JSON value")
}

private data class ProviderJsonValue(val text: String, val quoted: Boolean)

private class FlatProviderJsonParser(private val bytes: ByteArray) {
    private var index = 0

    fun parse(): Map<String, ProviderJsonValue> {
        require(bytes.all { it.toInt() in 0..127 })
        whitespace()
        expect('{')
        whitespace()
        val result = linkedMapOf<String, ProviderJsonValue>()
        if (peek('}')) {
            index++
        } else {
            while (true) {
                val key = string()
                require(result[key] == null)
                whitespace()
                expect(':')
                whitespace()
                val value = value()
                result[key] = value
                whitespace()
                when {
                    peek(',') -> { index++; whitespace() }
                    peek('}') -> { index++; break }
                    else -> throw IllegalArgumentException("Malformed JSON")
                }
            }
        }
        whitespace()
        require(index == bytes.size)
        return result
    }

    private fun value(depth: Int = 0): ProviderJsonValue {
        require(depth <= 16)
        return when {
            peek('"') -> ProviderJsonValue(string(), true)
            peek('{') -> {
                skipObject(depth + 1)
                ProviderJsonValue("object", false)
            }
            peek('[') -> {
                skipArray(depth + 1)
                ProviderJsonValue("array", false)
            }
            peek('t') -> { literal("true"); ProviderJsonValue("true", false) }
            peek('f') -> { literal("false"); ProviderJsonValue("false", false) }
            peek('n') -> { literal("null"); ProviderJsonValue("null", false) }
            else -> ProviderJsonValue(integer(), false)
        }
    }

    private fun skipObject(depth: Int) {
        require(depth <= 16)
        expect('{')
        whitespace()
        if (peek('}')) {
            index++
            return
        }
        while (true) {
            string()
            whitespace()
            expect(':')
            whitespace()
            value(depth)
            whitespace()
            when {
                peek(',') -> { index++; whitespace() }
                peek('}') -> { index++; return }
                else -> throw IllegalArgumentException("Malformed JSON object")
            }
        }
    }

    private fun skipArray(depth: Int) {
        require(depth <= 16)
        expect('[')
        whitespace()
        if (peek(']')) {
            index++
            return
        }
        while (true) {
            value(depth)
            whitespace()
            when {
                peek(',') -> { index++; whitespace() }
                peek(']') -> { index++; return }
                else -> throw IllegalArgumentException("Malformed JSON array")
            }
        }
    }

    private fun literal(expected: String) {
        require(index + expected.length <= bytes.size)
        val actual = bytes.copyOfRange(index, index + expected.length)
            .toString(StandardCharsets.US_ASCII)
        require(actual == expected)
        index += expected.length
    }

    private fun string(): String {
        expect('"')
        val value = StringBuilder()
        while (index < bytes.size) {
            val character = bytes[index++].toInt().toChar()
            require(character != '\\' && character.code >= 0x20)
            if (character == '"') return value.toString()
            value.append(character)
        }
        throw IllegalArgumentException("Unterminated string")
    }

    private fun integer(): String {
        val start = index
        if (peek('-')) index++
        require(index < bytes.size && bytes[index].toInt().toChar().isDigit())
        if (peek('0')) index++ else while (index < bytes.size && bytes[index].toInt().toChar().isDigit()) index++
        return bytes.copyOfRange(start, index).toString(StandardCharsets.US_ASCII)
    }

    private fun whitespace() {
        while (index < bytes.size && bytes[index].toInt().toChar() in charArrayOf(' ', '\n', '\r', '\t')) index++
    }

    private fun expect(expected: Char) {
        require(index < bytes.size && bytes[index++].toInt().toChar() == expected)
    }

    private fun peek(expected: Char): Boolean =
        index < bytes.size && bytes[index].toInt().toChar() == expected
}
