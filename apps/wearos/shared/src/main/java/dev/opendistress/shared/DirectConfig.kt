// SPDX-License-Identifier: MIT
package dev.opendistress.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Phone-authored direct-provider configuration committed to a watch. */
data class DirectConfig(
    val revision: Long,
    val grafanaWebhookUrl: String?,
    val pushoverUserKey: String?,
    val pushoverApiToken: String?,
    val protectedPersonName: String,
    val customAlertMessage: String,
    val homeAddress: String,
    val childrenInfo: String,
    val personDescription: String,
    val backgroundInfo: String,
    val responseInstructions: String,
    val profilePhotoUrl: String,
) {
    init {
        validate()
    }

    fun validate() {
        require(revision > 0) { "revision must be positive" }
        require(grafanaWebhookUrl != null || pushoverUserKey != null) {
            "at least one direct provider is required"
        }
        grafanaWebhookUrl?.let(::validateGrafanaWebhookUrl)
        require((pushoverUserKey == null) == (pushoverApiToken == null)) {
            "Pushover user key and API token must be configured together"
        }
        pushoverUserKey?.let(::validatePushoverToken)
        pushoverApiToken?.let(::validatePushoverToken)
        validateText("protectedPersonName", protectedPersonName, 40)
        validateText("customAlertMessage", customAlertMessage, 240)
        validateText("homeAddress", homeAddress, 120)
        validateText("childrenInfo", childrenInfo, 150)
        validateText("personDescription", personDescription, 150)
        validateText("backgroundInfo", backgroundInfo, 180)
        validateText("responseInstructions", responseInstructions, 180)
        validateText("profilePhotoUrl", profilePhotoUrl, 512)
        if (profilePhotoUrl.isNotEmpty()) validateProfilePhotoUrl(profilePhotoUrl)
    }

    fun canonicalBytes(): ByteArray {
        validate()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(SCHEMA_VERSION)
            data.writeLong(revision)
            data.writeNullableString(grafanaWebhookUrl)
            data.writeNullableString(pushoverUserKey)
            data.writeNullableString(pushoverApiToken)
            data.writeString(protectedPersonName)
            data.writeString(customAlertMessage)
            data.writeString(homeAddress)
            data.writeString(childrenInfo)
            data.writeString(personDescription)
            data.writeString(backgroundInfo)
            data.writeString(responseInstructions)
            data.writeString(profilePhotoUrl)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_CANONICAL_BYTES) { "configuration is too large" }
        }
    }

    fun digestSha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(canonicalBytes())

    companion object {
        private const val MAGIC = 0x4f444443 // ODDC
        private const val SCHEMA_VERSION = 1
        private const val MAX_CANONICAL_BYTES = 4_096
        private const val MAX_STRING_BYTES = 2_048
        private val PUSHOVER_TOKEN = Regex("[A-Za-z0-9]{30}")
        private val GRAFANA_TOKEN = Regex("[A-Za-z0-9_-]{16,128}")

        fun fromCanonicalBytes(bytes: ByteArray): DirectConfig {
            require(bytes.size in 1..MAX_CANONICAL_BYTES) { "invalid configuration size" }
            val input = ByteArrayInputStream(bytes)
            val config = DataInputStream(input).use { data ->
                require(data.readInt() == MAGIC) { "invalid configuration magic" }
                require(data.readInt() == SCHEMA_VERSION) { "unsupported configuration schema" }
                DirectConfig(
                    revision = data.readLong(),
                    grafanaWebhookUrl = data.readNullableString(),
                    pushoverUserKey = data.readNullableString(),
                    pushoverApiToken = data.readNullableString(),
                    protectedPersonName = data.readString(),
                    customAlertMessage = data.readString(),
                    homeAddress = data.readString(),
                    childrenInfo = data.readString(),
                    personDescription = data.readString(),
                    backgroundInfo = data.readString(),
                    responseInstructions = data.readString(),
                    profilePhotoUrl = data.readString(),
                ).also {
                    require(input.available() == 0) { "trailing configuration data" }
                }
            }
            require(MessageDigest.isEqual(bytes, config.canonicalBytes())) {
                "configuration encoding is not canonical"
            }
            return config
        }

        private fun validateGrafanaWebhookUrl(value: String) {
            require(value.length in 80..512) { "invalid Grafana webhook length" }
            val uri = runCatching { URI(value) }.getOrNull()
            require(uri != null && uri.scheme == "https" && uri.rawUserInfo == null) {
                "Grafana webhook must be HTTPS without user info"
            }
            require(uri.rawQuery == null && uri.rawFragment == null && uri.port == -1) {
                "Grafana webhook must not contain a port, query, or fragment"
            }
            val host = uri.host?.lowercase()
            require(host != null && host.length > 12 && host.endsWith(".grafana.net")) {
                "Grafana webhook must use a tenant grafana.net host"
            }
            val marker = "/integrations/v1/formatted_webhook/"
            val markerAt = uri.rawPath.indexOf(marker)
            require(markerAt >= 0 && uri.rawPath.endsWith('/')) {
                "invalid Grafana formatted-webhook path"
            }
            val token = uri.rawPath.substring(markerAt + marker.length, uri.rawPath.length - 1)
            require(GRAFANA_TOKEN.matches(token)) { "invalid Grafana webhook token" }
        }

        private fun validatePushoverToken(value: String) {
            require(PUSHOVER_TOKEN.matches(value)) { "invalid Pushover token" }
        }

        private fun validateProfilePhotoUrl(value: String) {
            val uri = runCatching { URI(value) }.getOrNull()
            require(
                uri != null && uri.scheme == "https" && uri.host != null &&
                    uri.rawUserInfo == null && uri.rawFragment == null,
            ) { "profile photo must be an HTTPS URL without user info or fragment" }
        }

        private fun validateText(name: String, value: String, maxLength: Int) {
            require(value.length <= maxLength) { "$name is too long" }
            require(value.none { it == '\u0000' || (it < ' ' && it != '\n' && it != '\t' && it != '\r') }) {
                "$name contains unsupported control characters"
            }
        }

        private fun DataOutputStream.writeNullableString(value: String?) {
            if (value == null) {
                writeInt(-1)
            } else {
                writeString(value)
            }
        }

        private fun DataOutputStream.writeString(value: String) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            require(encoded.size <= MAX_STRING_BYTES) { "encoded string is too large" }
            writeInt(encoded.size)
            write(encoded)
        }

        private fun DataInputStream.readNullableString(): String? {
            val length = readInt()
            return if (length == -1) null else readString(length)
        }

        private fun DataInputStream.readString(): String = readString(readInt())

        private fun DataInputStream.readString(length: Int): String {
            require(length in 0..MAX_STRING_BYTES) { "invalid encoded string length" }
            val encoded = ByteArray(length)
            readFully(encoded)
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString()
        }
    }
}
