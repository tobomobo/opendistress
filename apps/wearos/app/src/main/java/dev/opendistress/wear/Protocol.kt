// SPDX-License-Identifier: MIT
package dev.opendistress.wear

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val PROTOCOL_MAX = 2_147_483_647L
private const val UINT32_MAX = 4_294_967_295L
private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{21}[AQgw]$")
private val DIGEST_PATTERN = Regex("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")

internal data class EncryptedPayload(
    val keyVersion: Long,
    val iv: String,
    val ciphertext: String,
    val tag: String,
)

internal data class IncidentEvent(
    val eventId: String,
    val incidentId: String,
    val deviceId: String,
    val kind: String,
    val sequence: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val payload: EncryptedPayload,
    val requestSignature: String,
) {
    fun wireJson(): String = buildString(384) {
        append("{\"v\":2,\"event_id\":\"")
        append(eventId)
        append("\",\"incident_id\":\"")
        append(incidentId)
        append("\",\"device_id\":\"")
        append(deviceId)
        append("\",\"kind\":\"")
        append(kind)
        append("\",\"sequence\":")
        append(sequence)
        append(",\"created_at\":")
        append(createdAt)
        append(",\"expires_at\":")
        append(expiresAt)
        append(",\"payload\":{\"key_version\":")
        append(payload.keyVersion)
        append(",\"iv\":\"")
        append(payload.iv)
        append("\",\"ciphertext\":\"")
        append(payload.ciphertext)
        append("\",\"tag\":\"")
        append(payload.tag)
        append("\"}}")
    }
}

internal data class StatusQuery(
    val requestId: String,
    val incidentId: String,
    val deviceId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val requestSignature: String,
) {
    fun wireJson(): String =
        "{\"v\":2,\"request_id\":\"$requestId\",\"incident_id\":\"$incidentId\"," +
            "\"device_id\":\"$deviceId\",\"created_at\":$createdAt,\"expires_at\":$expiresAt}"
}

internal data class VerifiedIncidentStatus(
    val state: String,
    val checkedAt: Long,
)

internal class RuntimeConfig(
    val endpoint: URL,
    val deviceId: String,
    val authKey: ByteArray,
    val encryptionKey: ByteArray,
    val macKey: ByteArray,
    val keyVersion: Long,
    val templateId: ByteArray,
    val ttlSeconds: Long,
) {
    companion object {
        fun fromBuildConfig(): RuntimeConfig {
            val uri = try {
                URI(BuildConfig.OPENDISTRESS_ENDPOINT)
            } catch (error: Exception) {
                throw IllegalArgumentException("Endpoint is not a valid URI", error)
            }
            require(
                uri.scheme == "https" &&
                    uri.host != null &&
                    uri.host != "invalid.example" &&
                    uri.userInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    uri.rawPath == "/v2/events",
            ) { "Endpoint must be a configured HTTPS /v2/events URL" }
            Protocol.decodeCanonical(BuildConfig.OPENDISTRESS_DEVICE_ID, 16, ID_PATTERN)
            val auth = Protocol.decodeHex(BuildConfig.OPENDISTRESS_AUTH_KEY_HEX, 32)
            val enc = Protocol.decodeHex(BuildConfig.OPENDISTRESS_ENC_KEY_HEX, 32)
            val mac = Protocol.decodeHex(BuildConfig.OPENDISTRESS_MAC_KEY_HEX, 32)
            val configuredKeys = arrayOf(auth, enc, mac)
            require(configuredKeys.none { configured ->
                PUBLIC_VECTOR_KEYS.any { published -> MessageDigest.isEqual(configured, published) }
            }) { "Published protocol fixture keys are not valid provisioning" }
            require(!MessageDigest.isEqual(auth, enc)) { "Authentication and encryption keys must differ" }
            require(!MessageDigest.isEqual(auth, mac)) { "Authentication and MAC keys must differ" }
            require(!MessageDigest.isEqual(enc, mac)) { "Encryption and MAC keys must differ" }
            require(BuildConfig.OPENDISTRESS_KEY_VERSION in 1..PROTOCOL_MAX) { "Invalid key version" }
            require(BuildConfig.OPENDISTRESS_TTL_SECONDS in 1..86_400) { "Invalid incident lifetime" }
            return RuntimeConfig(
                endpoint = uri.toURL(),
                deviceId = BuildConfig.OPENDISTRESS_DEVICE_ID,
                authKey = auth,
                encryptionKey = enc,
                macKey = mac,
                keyVersion = BuildConfig.OPENDISTRESS_KEY_VERSION,
                templateId = Protocol.decodeHex(BuildConfig.OPENDISTRESS_TEMPLATE_ID_HEX, 16),
                ttlSeconds = BuildConfig.OPENDISTRESS_TTL_SECONDS,
            )
        }

        private val PUBLIC_VECTOR_KEYS = arrayOf(
            Protocol.decodeHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", 32),
            Protocol.decodeHex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f", 32),
            Protocol.decodeHex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f", 32),
        )
    }
}

internal data class LocationSample(
    val captureAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val quality: Int,
    val path: Int,
)

internal data class LocationPoint(
    val latitudeE7: Int?,
    val longitudeE7: Int?,
    val quality: Int,
)

internal object Protocol {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun randomId(): String = encode(randomBytes(16))

    fun validateId(value: String) {
        decodeCanonical(value, 16, ID_PATTERN)
    }

    fun createLive(config: RuntimeConfig, now: Long): IncidentEvent {
        require(now in 0..PROTOCOL_MAX)
        val expiresAt = Math.addExact(now, config.ttlSeconds)
        require(expiresAt <= PROTOCOL_MAX)
        val incidentId = randomId()
        val plaintext = config.templateId.copyOf()
        return try {
            sealEvent(
                eventId = incidentId,
                incidentId = incidentId,
                deviceId = config.deviceId,
                kind = "live.triggered",
                sequence = 0,
                createdAt = now,
                expiresAt = expiresAt,
                keyVersion = config.keyVersion,
                plaintext = plaintext,
                ivBytes = randomBytes(16),
                authKey = config.authKey,
                encryptionKey = config.encryptionKey,
                macKey = config.macKey,
            )
        } finally {
            plaintext.fill(0)
        }
    }

    fun createLocation(
        config: RuntimeConfig,
        incidentId: String,
        sequence: Long,
        createdAt: Long,
        expiresAt: Long,
        sample: LocationSample,
    ): IncidentEvent {
        require(sample.captureAt == 0L || sample.captureAt <= createdAt)
        val plaintext = locationBlock(sample)
        return try {
            sealEvent(
                eventId = randomId(),
                incidentId = incidentId,
                deviceId = config.deviceId,
                kind = "location.updated",
                sequence = sequence,
                createdAt = createdAt,
                expiresAt = expiresAt,
                keyVersion = config.keyVersion,
                plaintext = plaintext,
                ivBytes = randomBytes(16),
                authKey = config.authKey,
                encryptionKey = config.encryptionKey,
                macKey = config.macKey,
            )
        } finally {
            plaintext.fill(0)
        }
    }

    fun createStatusQuery(config: RuntimeConfig, plan: CapturePlan, now: Long): StatusQuery {
        require(plan.deviceId == config.deviceId && plan.keyVersion == config.keyVersion)
        var requestId: String
        do {
            requestId = randomId()
        } while (requestId == plan.incidentId || requestId == plan.deviceId)
        return statusQuery(
            requestId = requestId,
            incidentId = plan.incidentId,
            deviceId = plan.deviceId,
            createdAt = now,
            expiresAt = plan.expiresAt,
            authKey = config.authKey,
        )
    }

    internal fun statusQuery(
        requestId: String,
        incidentId: String,
        deviceId: String,
        createdAt: Long,
        expiresAt: Long,
        authKey: ByteArray,
    ): StatusQuery {
        validateId(requestId)
        validateId(incidentId)
        validateId(deviceId)
        require(requestId != incidentId && requestId != deviceId)
        require(createdAt in 0..PROTOCOL_MAX)
        require(expiresAt in 1..PROTOCOL_MAX)
        require(createdAt < expiresAt)
        require(authKey.size == 32)
        val signature = "v2=${encode(hmac(authKey, statusQueryCanonical(
            requestId,
            incidentId,
            deviceId,
            createdAt,
            expiresAt,
        )))}"
        return StatusQuery(
            requestId,
            incidentId,
            deviceId,
            createdAt,
            expiresAt,
            signature,
        ).also { require(it.wireJson().toByteArray(StandardCharsets.US_ASCII).size <= 1024) }
    }

    internal fun sealEvent(
        eventId: String,
        incidentId: String,
        deviceId: String,
        kind: String,
        sequence: Long,
        createdAt: Long,
        expiresAt: Long,
        keyVersion: Long,
        plaintext: ByteArray,
        ivBytes: ByteArray,
        authKey: ByteArray,
        encryptionKey: ByteArray,
        macKey: ByteArray,
    ): IncidentEvent {
        require(plaintext.size == 16) { "v2 plaintext must be one AES block" }
        require(ivBytes.size == 16)
        require(authKey.size == 32 && encryptionKey.size == 32 && macKey.size == 32)
        require(kind == "live.triggered" || kind == "location.updated")
        require(sequence in 0..PROTOCOL_MAX)
        require(createdAt in 0..PROTOCOL_MAX)
        require(expiresAt in createdAt..PROTOCOL_MAX)
        require(expiresAt - createdAt in 1..MAX_EVENT_LIFETIME_SECONDS)
        require(keyVersion in 1..PROTOCOL_MAX)
        decodeCanonical(eventId, 16, ID_PATTERN)
        decodeCanonical(incidentId, 16, ID_PATTERN)
        decodeCanonical(deviceId, 16, ID_PATTERN)
        if (kind == "live.triggered") {
            require(sequence == 0L && eventId == incidentId)
        } else {
            require(sequence >= 1L && eventId != incidentId)
        }

        val iv = encode(ivBytes)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(encryptionKey, "AES"),
            IvParameterSpec(ivBytes),
        )
        val ciphertextBytes = cipher.doFinal(plaintext)
        require(ciphertextBytes.size == 16)
        val ciphertext = encode(ciphertextBytes)
        val unsignedPayload = EncryptedPayload(keyVersion, iv, ciphertext, "")
        val content = contentCanonical(
            eventId,
            incidentId,
            deviceId,
            kind,
            sequence,
            createdAt,
            expiresAt,
            unsignedPayload,
        )
        val tag = encode(hmac(macKey, content))
        val payload = unsignedPayload.copy(tag = tag)
        val request = requestCanonical(
            eventId,
            incidentId,
            deviceId,
            kind,
            sequence,
            createdAt,
            expiresAt,
            payload,
        )
        val event = IncidentEvent(
            eventId,
            incidentId,
            deviceId,
            kind,
            sequence,
            createdAt,
            expiresAt,
            payload,
            "v2=${encode(hmac(authKey, request))}",
        )
        validateStored(event)
        return event
    }

    internal fun locationPoint(sample: LocationSample): LocationPoint {
        require(sample.captureAt in 0..UINT32_MAX)
        require(sample.quality in 0..4)
        require(sample.path == 0 || sample.path == 1)
        val unavailable = sample.quality == 0
        if (unavailable) {
            require(sample.captureAt == 0L && sample.latitude == null && sample.longitude == null)
        } else {
            require(sample.captureAt > 0)
            require(sample.latitude != null && sample.longitude != null)
        }
        val latitude = scaledCoordinate(sample.latitude, -90.0, 90.0)
        val longitude = scaledCoordinate(sample.longitude, -180.0, 180.0)
        return LocationPoint(
            latitudeE7 = if (unavailable) null else latitude,
            longitudeE7 = if (unavailable) null else longitude,
            quality = sample.quality,
        )
    }

    internal fun locationBlock(sample: LocationSample): ByteArray {
        val point = locationPoint(sample)
        return ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .put(0x01.toByte())
            .put(0x02.toByte())
            .putInt(sample.captureAt.toInt())
            .putInt(point.latitudeE7 ?: 0)
            .putInt(point.longitudeE7 ?: 0)
            .put(sample.quality.toByte())
            .put(sample.path.toByte())
            .array()
    }

    private fun scaledCoordinate(value: Double?, minimum: Double, maximum: Double): Int {
        if (value == null) return 0
        require(value.isFinite() && value in minimum..maximum)
        val scaled = (value * 10_000_000.0).toLong()
        require(scaled in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return scaled.toInt()
    }

    fun validateStored(event: IncidentEvent) {
        decodeCanonical(event.eventId, 16, ID_PATTERN)
        decodeCanonical(event.incidentId, 16, ID_PATTERN)
        decodeCanonical(event.deviceId, 16, ID_PATTERN)
        require(event.kind == "live.triggered" || event.kind == "location.updated")
        require(event.sequence in 0..PROTOCOL_MAX)
        require(event.createdAt in 0..PROTOCOL_MAX)
        require(event.expiresAt in event.createdAt..PROTOCOL_MAX)
        require(event.expiresAt - event.createdAt in 1..MAX_EVENT_LIFETIME_SECONDS)
        require(event.payload.keyVersion in 1..PROTOCOL_MAX)
        decodeCanonical(event.payload.iv, 16, ID_PATTERN)
        decodeCanonical(event.payload.ciphertext, 16, ID_PATTERN)
        decodeCanonical(event.payload.tag, 32, DIGEST_PATTERN)
        require(event.requestSignature.startsWith("v2="))
        decodeCanonical(event.requestSignature.removePrefix("v2="), 32, DIGEST_PATTERN)
        if (event.kind == "live.triggered") {
            require(event.sequence == 0L && event.eventId == event.incidentId)
        } else {
            require(event.sequence >= 1L && event.eventId != event.incidentId)
        }
        require(event.wireJson().toByteArray(StandardCharsets.US_ASCII).size <= 1024)
    }

    fun verifyContentTag(event: IncidentEvent, macKey: ByteArray): Boolean {
        if (macKey.size != 32) return false
        return try {
            val expected = hmac(
                macKey,
                contentCanonical(
                    event.eventId,
                    event.incidentId,
                    event.deviceId,
                    event.kind,
                    event.sequence,
                    event.createdAt,
                    event.expiresAt,
                    event.payload,
                ),
            )
            MessageDigest.isEqual(
                expected,
                decodeCanonical(event.payload.tag, 32, DIGEST_PATTERN),
            )
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun verifyAcceptedResponse(body: ByteArray, event: IncidentEvent, authKey: ByteArray): Boolean {
        if (body.isEmpty() || body.size > 512 || authKey.size != 32) return false
        val fields = try {
            FlatJsonParser(body).parse()
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (fields.keys != setOf("v", "event_id", "result", "response_signature")) return false
        if (fields["v"] != JsonScalar("2", false)) return false
        if (fields["event_id"] != JsonScalar(event.eventId, true)) return false
        if (fields["result"] != JsonScalar("durably_accepted", true)) return false
        val signature = fields["response_signature"]
        if (signature?.quoted != true || !signature.text.startsWith("v2=")) return false
        val supplied = try {
            decodeCanonical(signature.text.removePrefix("v2="), 32, DIGEST_PATTERN)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val expected = hmac(
            authKey,
            (
                "opendistress.result.v2\n" +
                    "v=2\n" +
                    "event_id=${event.eventId}\n" +
                    "result=durably_accepted\n"
                ).toByteArray(StandardCharsets.US_ASCII),
        )
        return MessageDigest.isEqual(expected, supplied)
    }

    fun verifyStatusResponse(
        body: ByteArray,
        query: StatusQuery,
        authKey: ByteArray,
        receivedAt: Long,
    ): VerifiedIncidentStatus? {
        if (body.isEmpty() || body.size > 512 || authKey.size != 32) return null
        if (
            query.createdAt !in 0..PROTOCOL_MAX ||
            query.expiresAt !in 1..PROTOCOL_MAX ||
            query.createdAt >= query.expiresAt
        ) return null
        if (receivedAt !in 0..PROTOCOL_MAX || receivedAt > query.createdAt + STATUS_CLOCK_SKEW_SECONDS) {
            return null
        }
        val fields = try {
            FlatJsonParser(body).parse()
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (
            fields.keys != setOf(
                "v",
                "request_id",
                "incident_id",
                "device_id",
                "state",
                "checked_at",
                "response_signature",
            )
        ) return null
        if (fields["v"] != JsonScalar("2", false)) return null
        if (fields["request_id"] != JsonScalar(query.requestId, true)) return null
        if (fields["incident_id"] != JsonScalar(query.incidentId, true)) return null
        if (fields["device_id"] != JsonScalar(query.deviceId, true)) return null
        val state = fields["state"]?.takeIf { it.quoted }?.text ?: return null
        if (state !in STATUS_STATES) return null
        val checkedAtField = fields["checked_at"]?.takeIf { !it.quoted }?.text ?: return null
        val checkedAt = checkedAtField.toLongOrNull()?.takeIf { it in 0..PROTOCOL_MAX } ?: return null
        if (
            checkedAt < query.createdAt - STATUS_CLOCK_SKEW_SECONDS ||
            checkedAt > receivedAt + STATUS_CLOCK_SKEW_SECONDS
        ) return null
        val signature = fields["response_signature"]?.takeIf { it.quoted }?.text ?: return null
        if (!signature.startsWith("v2=")) return null
        val supplied = try {
            decodeCanonical(signature.removePrefix("v2="), 32, DIGEST_PATTERN)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val expected = hmac(
            authKey,
            statusResponseCanonical(
                query.requestId,
                query.incidentId,
                query.deviceId,
                state,
                checkedAt,
            ),
        )
        return if (MessageDigest.isEqual(expected, supplied)) {
            VerifiedIncidentStatus(state, checkedAt)
        } else {
            null
        }
    }

    private fun statusQueryCanonical(
        requestId: String,
        incidentId: String,
        deviceId: String,
        createdAt: Long,
        expiresAt: Long,
    ): ByteArray = (
        "opendistress.status.query.v2\n" +
            "method=POST\n" +
            "v=2\n" +
            "request_id=$requestId\n" +
            "incident_id=$incidentId\n" +
            "device_id=$deviceId\n" +
            "created_at=$createdAt\n" +
            "expires_at=$expiresAt\n"
        ).toByteArray(StandardCharsets.US_ASCII)

    private fun statusResponseCanonical(
        requestId: String,
        incidentId: String,
        deviceId: String,
        state: String,
        checkedAt: Long,
    ): ByteArray = (
        "opendistress.status.result.v2\n" +
            "v=2\n" +
            "request_id=$requestId\n" +
            "incident_id=$incidentId\n" +
            "device_id=$deviceId\n" +
            "state=$state\n" +
            "checked_at=$checkedAt\n"
        ).toByteArray(StandardCharsets.US_ASCII)

    internal fun contentCanonical(
        eventId: String,
        incidentId: String,
        deviceId: String,
        kind: String,
        sequence: Long,
        createdAt: Long,
        expiresAt: Long,
        payload: EncryptedPayload,
    ): ByteArray = (
        "opendistress.content.v2\n" +
            "v=2\n" +
            "event_id=$eventId\n" +
            "incident_id=$incidentId\n" +
            "device_id=$deviceId\n" +
            "kind=$kind\n" +
            "sequence=$sequence\n" +
            "created_at=$createdAt\n" +
            "expires_at=$expiresAt\n" +
            "payload.key_version=${payload.keyVersion}\n" +
            "payload.iv=${payload.iv}\n" +
            "payload.ciphertext=${payload.ciphertext}\n"
        ).toByteArray(StandardCharsets.US_ASCII)

    internal fun requestCanonical(
        eventId: String,
        incidentId: String,
        deviceId: String,
        kind: String,
        sequence: Long,
        createdAt: Long,
        expiresAt: Long,
        payload: EncryptedPayload,
    ): ByteArray = (
        "opendistress.submit.v2\n" +
            "method=POST\n" +
            "v=2\n" +
            "event_id=$eventId\n" +
            "incident_id=$incidentId\n" +
            "device_id=$deviceId\n" +
            "kind=$kind\n" +
            "sequence=$sequence\n" +
            "created_at=$createdAt\n" +
            "expires_at=$expiresAt\n" +
            "payload.key_version=${payload.keyVersion}\n" +
            "payload.iv=${payload.iv}\n" +
            "payload.ciphertext=${payload.ciphertext}\n" +
            "payload.tag=${payload.tag}\n"
        ).toByteArray(StandardCharsets.US_ASCII)

    internal fun decodeHex(value: String, byteCount: Int): ByteArray {
        require(value.length == byteCount * 2 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
        return ByteArray(byteCount) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    internal fun decodeCanonical(value: String, byteCount: Int, pattern: Regex): ByteArray {
        require(pattern.matches(value)) { "Non-canonical base64url" }
        val decoded = try {
            decoder.decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid base64url", error)
        }
        require(decoded.size == byteCount && encode(decoded) == value) { "Non-canonical base64url" }
        return decoded
    }

    private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(bytes)
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
}

internal const val MAX_EVENT_LIFETIME_SECONDS = 86_400L
private const val STATUS_CLOCK_SKEW_SECONDS = 300L
private val STATUS_STATES = setOf("active", "acknowledged", "resolved", "expired")

private data class JsonScalar(val text: String, val quoted: Boolean)

private class FlatJsonParser(private val bytes: ByteArray) {
    private var index = 0

    fun parse(): Map<String, JsonScalar> {
        require(bytes.all { byte ->
            val value = byte.toInt() and 0xff
            value == 0x09 || value == 0x0a || value == 0x0d || value in 0x20..0x7e
        }) { "Response must be ASCII JSON" }
        skipWhitespace()
        expect('{')
        skipWhitespace()
        val result = linkedMapOf<String, JsonScalar>()
        if (peek('}')) {
            index++
        } else {
            while (true) {
                skipWhitespace()
                val key = parseString()
                require(!result.containsKey(key)) { "Duplicate response member" }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = if (peek('"')) {
                    JsonScalar(parseString(), true)
                } else {
                    JsonScalar(parseIntegerToken(), false)
                }
                result[key] = value
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        break
                    }
                    else -> throw IllegalArgumentException("Malformed response object")
                }
            }
        }
        skipWhitespace()
        require(index == bytes.size) { "Trailing response data" }
        return result
    }

    private fun parseString(): String {
        expect('"')
        val output = ByteArrayOutputStream()
        while (index < bytes.size) {
            val value = bytes[index++].toInt() and 0xff
            when {
                value == '"'.code -> return output.toString(StandardCharsets.US_ASCII.name())
                value == '\\'.code || value < 0x20 || value > 0x7e ->
                    throw IllegalArgumentException("Escaped or non-ASCII response string")
                else -> output.write(value)
            }
        }
        throw IllegalArgumentException("Unterminated response string")
    }

    private fun parseIntegerToken(): String {
        val start = index
        while (index < bytes.size && (bytes[index].toInt() and 0xff) in '0'.code..'9'.code) {
            index++
        }
        require(index > start) { "Expected integer response token" }
        val token = String(bytes, start, index - start, StandardCharsets.US_ASCII)
        require(token == "0" || !token.startsWith('0')) { "Non-canonical integer response token" }
        return token
    }

    private fun skipWhitespace() {
        while (index < bytes.size && bytes[index].toInt().toChar() in " \t\r\n") index++
    }

    private fun expect(character: Char) {
        require(peek(character)) { "Expected $character" }
        index++
    }

    private fun peek(character: Char): Boolean =
        index < bytes.size && (bytes[index].toInt() and 0xff) == character.code
}
