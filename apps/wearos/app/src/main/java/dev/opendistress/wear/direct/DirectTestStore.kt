// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.opendistress.shared.DirectConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class DirectRouteStatus { PENDING, ACCEPTED, REJECTED, SKIPPED }

internal data class DirectRouteState(
    val provider: DirectProvider,
    val configurationFingerprint: String,
    val status: DirectRouteStatus,
    val acceptanceReference: String = "",
    val emergencyReceipt: String = "",
    /** Provider-side end of Pushover emergency repeats, measured from actual acceptance. */
    val emergencyRepeatsUntil: Long = 0,
)

internal data class DirectTestState(
    val incidentId: String,
    val createdAt: Long,
    val triggerExpiresAt: Long,
    val profileRevision: Long,
    val routes: List<DirectRouteState>,
    val queue: List<DirectHttpRequest>,
    val acceptedAt: Long? = null,
    val trackingExpiresAt: Long? = null,
    val nextLocationSequence: Long = 1,
)

internal fun DirectTestState.isResetPending(): Boolean =
    queue.any { it.kind == DirectRequestKind.CANCEL }

internal fun DirectTestState.pushoverEmergencyRepeatsUntil(): Long =
    routes.firstOrNull {
        it.provider == DirectProvider.PUSHOVER && it.status == DirectRouteStatus.ACCEPTED
    }?.emergencyRepeatsUntil ?: 0

internal fun DirectTestState.availableLocationProviders(config: DirectConfig): Set<DirectProvider> =
    routes.asSequence()
        .filter { route ->
            route.status == DirectRouteStatus.ACCEPTED &&
                currentDirectFingerprint(config, route.provider) == route.configurationFingerprint
        }
        .filterNot { route ->
            queue.any { request ->
                request.kind == DirectRequestKind.LOCATION && request.provider == route.provider
            }
        }
        .map(DirectRouteState::provider)
        .toSet()

internal fun currentDirectFingerprint(config: DirectConfig, provider: DirectProvider): String? = when (provider) {
    DirectProvider.GRAFANA -> config.grafanaWebhookUrl
        ?.takeIf(DirectGrafanaAdapter::isWebhookUrl)
        ?.let(DirectProviderFingerprint::grafana)
    DirectProvider.PUSHOVER -> {
        val user = config.pushoverUserKey
        val token = config.pushoverApiToken
        if (DirectPushoverAdapter.isToken(user) && DirectPushoverAdapter.isToken(token)) {
            DirectProviderFingerprint.pushover(requireNotNull(user), requireNotNull(token))
        } else null
    }
}

internal class DirectTestStore(
    context: Context,
    private val cipher: DirectStateCipher = AndroidKeystoreDirectStateCipher(),
) {
    private val directory = context.filesDir.resolve("direct-test")
    private val atomic = AtomicFile(directory.resolve("incident.bin"))
    private var state: DirectTestState?

    init {
        require(directory.exists() || directory.mkdirs()) { "Cannot create direct TEST store" }
        state = read()
    }

    @Synchronized
    fun snapshot(): DirectTestState? = state?.copy(
        routes = state!!.routes.toList(),
        queue = state!!.queue.toList(),
    )

    @Synchronized
    fun begin(config: DirectConfig, now: Long): DirectTestState {
        require(state == null) { "A direct TEST is already active" }
        require(now >= 0 && config.revision > 0)
        val incidentId = directId()
        val expiresAt = Math.addExact(now, TRIGGER_LIFETIME_SECONDS)
        val routes = mutableListOf<DirectRouteState>()
        val requests = mutableListOf<DirectHttpRequest>()
        config.grafanaWebhookUrl?.takeIf(DirectGrafanaAdapter::isWebhookUrl)?.let {
            val request = DirectGrafanaAdapter.trigger(config, incidentId, now, expiresAt)
            routes += DirectRouteState(DirectProvider.GRAFANA, request.configurationFingerprint, DirectRouteStatus.PENDING)
            requests += request
        }
        if (
            DirectPushoverAdapter.isToken(config.pushoverUserKey) &&
            DirectPushoverAdapter.isToken(config.pushoverApiToken)
        ) {
            val request = DirectPushoverAdapter.trigger(config, incidentId, now, expiresAt)
            routes += DirectRouteState(DirectProvider.PUSHOVER, request.configurationFingerprint, DirectRouteStatus.PENDING)
            requests += request
        }
        require(routes.isNotEmpty()) { "At least one direct TEST provider is required" }
        return DirectTestState(
            incidentId,
            now,
            expiresAt,
            config.revision,
            routes,
            requests,
        ).also(::replace)
    }

    /** Keeps the exact same request bytes and identifier until evidence changes its state. */
    @Synchronized
    fun nextRequest(config: DirectConfig, now: Long): DirectHttpRequest? =
        state?.queue?.firstOrNull { request ->
            now < request.expiresAt &&
                currentDirectFingerprint(config, request.provider) == request.configurationFingerprint
        }

    @Synchronized
    fun recordTriggerAccepted(
        requestId: String,
        acceptance: DirectProviderAcceptance,
        acceptedAt: Long,
    ): DirectTestState {
        val next = DirectTestTransitions.triggerAccepted(
            requireNotNull(state), requestId, acceptance, acceptedAt,
        )
        replace(next)
        return next
    }

    @Synchronized
    fun recordDefiniteRejection(requestId: String): DirectTestState {
        val next = DirectTestTransitions.definiteRejection(requireNotNull(state), requestId)
        replace(next)
        return next
    }

    @Synchronized
    fun recordLocationAccepted(requestId: String): DirectTestState {
        return DirectTestTransitions.locationAccepted(requireNotNull(state), requestId).also(::replace)
    }

    @Synchronized
    fun recordRetryableAttempt(requestId: String): DirectTestState {
        return DirectTestTransitions.retryableAttempt(requireNotNull(state), requestId).also(::replace)
    }

    @Synchronized
    fun recordCancellationAccepted(requestId: String, acceptance: DirectProviderAcceptance) {
        val current = requireNotNull(state)
        val request = current.queue.firstOrNull { it.requestId == requestId }
            ?: throw IllegalArgumentException("Unknown direct request")
        require(request.kind == DirectRequestKind.CANCEL)
        require(request.provider == DirectProvider.PUSHOVER && acceptance.provider == DirectProvider.PUSHOVER)
        require(DirectPushoverAdapter.isRequestReference(acceptance.reference))
        clear()
        state = null
    }

    @Synchronized
    fun queueLocation(config: DirectConfig, fix: DirectLocationFix, now: Long): DirectTestState {
        val current = requireNotNull(state)
        val trackingExpiry = requireNotNull(current.trackingExpiresAt) { "No provider accepted the TEST" }
        require(now < trackingExpiry) { "Direct TEST tracking expired" }
        val update = DirectLocationFormatter.format(current.nextLocationSequence, now, fix)
        val availableProviders = current.availableLocationProviders(config)
        val newRequests = current.routes.mapNotNull { route ->
            if (route.provider !in availableProviders) return@mapNotNull null
            when (route.provider) {
                DirectProvider.GRAFANA -> {
                    val configured = config.grafanaWebhookUrl ?: return@mapNotNull null
                    if (!DirectGrafanaAdapter.isWebhookUrl(configured)) return@mapNotNull null
                    if (DirectProviderFingerprint.grafana(configured) != route.configurationFingerprint) {
                        return@mapNotNull null
                    }
                    DirectGrafanaAdapter.location(config, current.incidentId, now, trackingExpiry, update)
                }
                DirectProvider.PUSHOVER -> {
                    val user = config.pushoverUserKey ?: return@mapNotNull null
                    val token = config.pushoverApiToken ?: return@mapNotNull null
                    if (!DirectPushoverAdapter.isToken(user) || !DirectPushoverAdapter.isToken(token)) {
                        return@mapNotNull null
                    }
                    if (DirectProviderFingerprint.pushover(user, token) != route.configurationFingerprint) {
                        return@mapNotNull null
                    }
                    DirectPushoverAdapter.location(config, current.incidentId, now, trackingExpiry, update)
                }
            }
        }
        require(newRequests.isNotEmpty()) { "No accepted provider matches current settings" }
        require(current.queue.size + newRequests.size <= MAX_REQUESTS)
        val next = current.copy(
            queue = current.queue + newRequests,
            nextLocationSequence = Math.addExact(current.nextLocationSequence, 1),
        )
        replace(next)
        return next
    }

    @Synchronized
    fun hasAcceptedLocationTarget(config: DirectConfig): Boolean {
        val current = state ?: return false
        return current.acceptedAt != null && current.routes.any { route ->
            route.status == DirectRouteStatus.ACCEPTED &&
                currentDirectFingerprint(config, route.provider) == route.configurationFingerprint
        }
    }

    @Synchronized
    fun requestAcceptedTestReset(config: DirectConfig, now: Long): Boolean {
        val current = requireNotNull(state)
        requireNotNull(current.acceptedAt) { "A pending or unsent TEST cannot be silently reset" }
        if (current.isResetPending()) return false
        val pushover = current.routes.firstOrNull {
            it.provider == DirectProvider.PUSHOVER && it.status == DirectRouteStatus.ACCEPTED
        }
        val emergencyRepeatsUntil = current.pushoverEmergencyRepeatsUntil()
        if (pushover == null || now >= emergencyRepeatsUntil) {
            clear()
            state = null
            return true
        }
        require(currentDirectFingerprint(config, DirectProvider.PUSHOVER) == pushover.configurationFingerprint) {
            "Restore the accepted Pushover configuration before reset"
        }
        val cancel = DirectPushoverAdapter.cancel(
            config,
            current.incidentId,
            pushover.emergencyReceipt,
            now,
            emergencyRepeatsUntil,
        )
        replace(current.copy(queue = listOf(cancel)))
        return false
    }

    @Synchronized
    fun completeResetAfterEmergencyExpiry(now: Long) {
        val current = requireNotNull(state)
        require(current.isResetPending() && now >= current.pushoverEmergencyRepeatsUntil())
        clear()
        state = null
    }

    /** Used only by the debug-only emulator receiver, which never creates a real provider event. */
    @Synchronized
    fun clearAcceptedTestFixture() {
        requireNotNull(state?.acceptedAt)
        clear()
        state = null
    }

    @Synchronized
    fun resetDefinitivelyRejectedTest() {
        val current = requireNotNull(state)
        require(current.acceptedAt == null && current.queue.isEmpty())
        require(current.routes.all { it.status == DirectRouteStatus.REJECTED })
        clear()
        state = null
    }

    @Synchronized
    fun archiveExpiredTest(now: Long) {
        val current = requireNotNull(state)
        val terminalAt = current.trackingExpiresAt ?: current.triggerExpiresAt
        require(now >= terminalAt) { "An unexpired direct TEST cannot be archived" }
        clear()
        state = null
    }

    @Synchronized
    fun scrubExpiredLocation(now: Long): Boolean {
        val current = state ?: return false
        val trackingExpiry = current.trackingExpiresAt ?: return false
        if (now < trackingExpiry || current.queue.none { it.kind == DirectRequestKind.LOCATION }) return false
        replace(current.copy(queue = current.queue.filterNot { it.kind == DirectRequestKind.LOCATION }))
        return true
    }

    private fun replace(next: DirectTestState) {
        DirectTestStateCodec.validate(next)
        val plaintext = DirectTestStateCodec.encode(next)
        try {
            write(cipher.encrypt(plaintext))
            state = next
        } finally {
            plaintext.fill(0)
        }
    }

    private fun read(): DirectTestState? {
        val encrypted = try {
            atomic.openRead().use(::readBounded)
        } catch (_: FileNotFoundException) {
            return null
        }
        val plaintext = cipher.decrypt(encrypted)
        return try {
            DirectTestStateCodec.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun write(encrypted: ByteArray) {
        require(encrypted.size in 1..MAX_STATE_FILE_BYTES)
        var output: FileOutputStream? = null
        try {
            val stream = atomic.startWrite()
            output = stream
            stream.write(encrypted)
            stream.flush()
            atomic.finishWrite(stream)
            output = null
        } catch (error: Exception) {
            output?.let(atomic::failWrite)
            throw error
        }
    }

    private fun clear() {
        atomic.delete()
        require(!atomic.baseFile.exists()) { "Direct TEST state could not be removed" }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1_024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= MAX_STATE_FILE_BYTES) { "Direct TEST state is too large" }
            output.write(buffer, 0, count)
        }
    }

    companion object {
        private const val TRIGGER_LIFETIME_SECONDS = 900L
        private const val TRACKING_LIFETIME_SECONDS = 86_400L
        private const val MAX_REQUESTS = 16
        private const val MAX_STATE_FILE_BYTES = 131_072

        @Volatile
        private var instance: DirectTestStore? = null

        fun get(context: Context): DirectTestStore = instance ?: synchronized(this) {
            instance ?: DirectTestStore(context.applicationContext).also { instance = it }
        }
    }
}

internal object DirectTestTransitions {
    fun triggerAccepted(
        current: DirectTestState,
        requestId: String,
        acceptance: DirectProviderAcceptance,
        acceptedAt: Long,
    ): DirectTestState {
        val request = current.queue.firstOrNull { it.requestId == requestId }
            ?: throw IllegalArgumentException("Unknown direct request")
        require(request.kind == DirectRequestKind.TRIGGER && request.provider == acceptance.provider)
        require(acceptedAt >= request.createdAt)
        val route = current.routes.first { it.provider == request.provider }
        require(route.status == DirectRouteStatus.PENDING)
        require(route.configurationFingerprint == request.configurationFingerprint)
        if (request.provider == DirectProvider.PUSHOVER) {
            require(DirectPushoverAdapter.isRequestReference(acceptance.reference))
            require(DirectPushoverAdapter.isToken(acceptance.emergencyReceipt))
        }
        val firstAcceptance = current.acceptedAt ?: acceptedAt
        val trackingExpiry = current.trackingExpiresAt ?: Math.addExact(firstAcceptance, 86_400L)
        val emergencyRepeatsUntil = if (request.provider == DirectProvider.PUSHOVER) {
            Math.addExact(acceptedAt, request.expiresAt - request.createdAt)
        } else {
            0L
        }
        return current.copy(
            routes = current.routes.map {
                when {
                    it.provider == request.provider -> it.copy(
                        status = DirectRouteStatus.ACCEPTED,
                        acceptanceReference = acceptance.reference,
                        emergencyReceipt = acceptance.emergencyReceipt.orEmpty(),
                        emergencyRepeatsUntil = emergencyRepeatsUntil,
                    )
                    it.status == DirectRouteStatus.PENDING -> it.copy(status = DirectRouteStatus.SKIPPED)
                    else -> it
                }
            },
            queue = current.queue.filterNot {
                it.kind == DirectRequestKind.TRIGGER
            },
            acceptedAt = firstAcceptance,
            trackingExpiresAt = trackingExpiry,
        ).also(DirectTestStateCodec::validate)
    }

    fun definiteRejection(current: DirectTestState, requestId: String): DirectTestState {
        val request = current.queue.firstOrNull { it.requestId == requestId }
            ?: throw IllegalArgumentException("Unknown direct request")
        return current.copy(
            routes = if (request.kind == DirectRequestKind.TRIGGER) current.routes.map {
                if (it.provider == request.provider && it.status == DirectRouteStatus.PENDING) {
                    it.copy(status = DirectRouteStatus.REJECTED)
                } else it
            } else current.routes,
            queue = current.queue.filterNot { it.requestId == requestId },
        ).also(DirectTestStateCodec::validate)
    }

    fun locationAccepted(current: DirectTestState, requestId: String): DirectTestState {
        val request = current.queue.firstOrNull { it.requestId == requestId }
            ?: throw IllegalArgumentException("Unknown direct request")
        require(request.kind == DirectRequestKind.LOCATION)
        return current.copy(queue = current.queue.filterNot { it.requestId == requestId })
            .also(DirectTestStateCodec::validate)
    }

    fun retryableAttempt(current: DirectTestState, requestId: String): DirectTestState {
        val request = current.queue.firstOrNull { it.requestId == requestId }
            ?: throw IllegalArgumentException("Unknown direct request")
        return current.copy(
            queue = current.queue.filterNot { it.requestId == requestId } + request,
        ).also(DirectTestStateCodec::validate)
    }
}

internal interface DirectStateCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

internal class AndroidKeystoreDirectStateCipher : DirectStateCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeyStore generates the nonce when randomized encryption is required.
        // Supplying our own IV is rejected on real Android/Wear OS Keystore providers.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val nonce = requireNotNull(cipher.iv).also { require(it.size == 12) }
        val encrypted = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(STATE_ENCRYPTION_MAGIC)
                output.writeInt(1)
                output.write(nonce)
                output.writeInt(encrypted.size)
                output.write(encrypted)
            }
            bytes.toByteArray()
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        DataInputStream(ByteArrayInputStream(ciphertext)).use { input ->
            require(input.readInt() == STATE_ENCRYPTION_MAGIC)
            require(input.readInt() == 1)
            val nonce = ByteArray(12).also(input::readFully)
            val length = input.readInt()
            require(length in 17..131_000)
            val encrypted = ByteArray(length).also(input::readFully)
            require(input.read() == -1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce))
            return cipher.doFinal(encrypted)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }
        return requireNotNull(store.getKey(KEY_ALIAS, null) as? SecretKey)
    }

    private companion object {
        const val KEY_ALIAS = "opendistress.direct.state.aes.v1"
        const val STATE_ENCRYPTION_MAGIC = 0x4f445345
    }
}

internal object DirectTestStateCodec {
    fun encode(state: DirectTestState): ByteArray {
        validate(state)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(STATE_MAGIC)
                output.writeInt(STATE_VERSION)
                output.writeUTF(state.incidentId)
                output.writeLong(state.createdAt)
                output.writeLong(state.triggerExpiresAt)
                output.writeLong(state.profileRevision)
                output.writeBoolean(state.acceptedAt != null)
                state.acceptedAt?.let(output::writeLong)
                output.writeBoolean(state.trackingExpiresAt != null)
                state.trackingExpiresAt?.let(output::writeLong)
                output.writeLong(state.nextLocationSequence)
                output.writeInt(state.routes.size)
                state.routes.forEach { route ->
                    output.writeByte(route.provider.ordinal)
                    output.writeUTF(route.configurationFingerprint)
                    output.writeByte(route.status.ordinal)
                    output.writeUTF(route.acceptanceReference)
                    output.writeUTF(route.emergencyReceipt)
                    output.writeLong(route.emergencyRepeatsUntil)
                }
                output.writeInt(state.queue.size)
                state.queue.forEach { request ->
                    output.writeUTF(request.requestId)
                    output.writeUTF(request.incidentId)
                    output.writeByte(request.provider.ordinal)
                    output.writeUTF(request.configurationFingerprint)
                    output.writeUTF(request.endpoint)
                    output.writeUTF(request.contentType)
                    output.writeUTF(request.body)
                    output.writeByte(request.kind.ordinal)
                    output.writeLong(request.sequence)
                    output.writeLong(request.createdAt)
                    output.writeLong(request.expiresAt)
                }
            }
            bytes.toByteArray().also { require(it.size <= 120_000) }
        }
    }

    fun decode(bytes: ByteArray): DirectTestState {
        require(bytes.size in 1..120_000)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == STATE_MAGIC)
            val version = input.readInt()
            require(version in 1..STATE_VERSION)
            val incidentId = input.readUTF()
            val createdAt = input.readLong()
            val triggerExpiresAt = input.readLong()
            val profileRevision = input.readLong()
            val acceptedAt = if (input.readBoolean()) input.readLong() else null
            val trackingExpiresAt = if (input.readBoolean()) input.readLong() else null
            val nextSequence = input.readLong()
            val routeCount = input.readInt()
            require(routeCount in 1..2)
            val routes = List(routeCount) {
                val provider = enumAt<DirectProvider>(input.readUnsignedByte())
                val fingerprint = input.readUTF()
                val status = enumAt<DirectRouteStatus>(input.readUnsignedByte())
                val acceptanceReference = input.readUTF()
                val emergencyReceipt = input.readUTF()
                DirectRouteState(
                    provider = provider,
                    configurationFingerprint = fingerprint,
                    status = status,
                    acceptanceReference = acceptanceReference,
                    emergencyReceipt = emergencyReceipt,
                    emergencyRepeatsUntil = if (version >= 2) {
                        input.readLong()
                    } else if (
                        provider == DirectProvider.PUSHOVER && status == DirectRouteStatus.ACCEPTED
                    ) {
                        Math.addExact(requireNotNull(acceptedAt), 900L)
                    } else {
                        0L
                    },
                )
            }
            val requestCount = input.readInt()
            require(requestCount in 0..16)
            val queue = List(requestCount) {
                DirectHttpRequest(
                    requestId = input.readUTF(),
                    incidentId = input.readUTF(),
                    provider = enumAt<DirectProvider>(input.readUnsignedByte()),
                    configurationFingerprint = input.readUTF(),
                    endpoint = input.readUTF(),
                    contentType = input.readUTF(),
                    body = input.readUTF(),
                    kind = enumAt<DirectRequestKind>(input.readUnsignedByte()),
                    sequence = input.readLong(),
                    createdAt = input.readLong(),
                    expiresAt = input.readLong(),
                )
            }
            require(input.read() == -1)
            return DirectTestState(
                incidentId,
                createdAt,
                triggerExpiresAt,
                profileRevision,
                routes,
                queue,
                acceptedAt,
                trackingExpiresAt,
                nextSequence,
            ).also(::validate)
        }
    }

    fun validate(state: DirectTestState) {
        require(DIRECT_ID.matches(state.incidentId))
        require(state.createdAt >= 0 && state.triggerExpiresAt > state.createdAt)
        require(state.triggerExpiresAt - state.createdAt <= 900)
        require(state.profileRevision > 0)
        require(state.routes.size in 1..2 && state.routes.map { it.provider }.distinct().size == state.routes.size)
        require(state.queue.size <= 16 && state.queue.map { it.requestId }.distinct().size == state.queue.size)
        require(state.nextLocationSequence >= 1)
        require((state.acceptedAt == null) == (state.trackingExpiresAt == null))
        if (state.acceptedAt != null && state.trackingExpiresAt != null) {
            require(state.acceptedAt >= state.createdAt)
            require(state.trackingExpiresAt - state.acceptedAt == 86_400L)
            require(state.routes.any { it.status == DirectRouteStatus.ACCEPTED })
        }
        state.routes.forEach { route ->
            require(DIGEST.matches(route.configurationFingerprint))
            if (route.status == DirectRouteStatus.ACCEPTED) {
                require(route.acceptanceReference.isNotEmpty())
                if (route.provider == DirectProvider.PUSHOVER) require(DirectPushoverAdapter.isToken(route.emergencyReceipt))
                if (route.provider == DirectProvider.PUSHOVER) {
                    require(route.emergencyRepeatsUntil > requireNotNull(state.acceptedAt))
                    require(route.emergencyRepeatsUntil - state.acceptedAt <= 900L)
                } else {
                    require(route.emergencyRepeatsUntil == 0L)
                }
            } else {
                require(
                    route.acceptanceReference.isEmpty() &&
                        route.emergencyReceipt.isEmpty() &&
                        route.emergencyRepeatsUntil == 0L,
                )
            }
        }
        state.queue.forEach { request ->
            require(DIRECT_ID.matches(request.requestId) && request.incidentId == state.incidentId)
            val route = state.routes.first { it.provider == request.provider }
            require(request.configurationFingerprint == route.configurationFingerprint)
            require(request.body.toByteArray(Charsets.UTF_8).size <= 16_384)
            require(request.createdAt >= state.createdAt && request.createdAt < request.expiresAt)
            when (request.kind) {
                DirectRequestKind.TRIGGER ->
                    require(request.sequence == 0L && request.expiresAt == state.triggerExpiresAt)
                DirectRequestKind.LOCATION -> {
                    require(request.sequence in 1 until state.nextLocationSequence)
                    require(request.expiresAt == state.trackingExpiresAt)
                    require(route.status == DirectRouteStatus.ACCEPTED)
                }
                DirectRequestKind.CANCEL -> {
                    require(request.provider == DirectProvider.PUSHOVER)
                    require(request.sequence == 0L && request.expiresAt == state.pushoverEmergencyRepeatsUntil())
                    require(route.status == DirectRouteStatus.ACCEPTED)
                    require(state.queue.size == 1)
                    require(DirectPushoverAdapter.isCancellationEndpoint(request.endpoint))
                }
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumAt(ordinal: Int): T =
        enumValues<T>().getOrNull(ordinal) ?: throw IllegalArgumentException("Invalid enum value")

    private val DIRECT_ID = Regex("^[A-Za-z0-9_-]{22}$")
    private val DIGEST = Regex("^[A-Za-z0-9_-]{43}$")
    private const val STATE_MAGIC = 0x4f445453
    private const val STATE_VERSION = 2
}

private fun directId(): String {
    val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
