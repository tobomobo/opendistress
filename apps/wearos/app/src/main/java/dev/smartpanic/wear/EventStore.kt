// SPDX-License-Identifier: MIT
package dev.smartpanic.wear

import android.content.Context
import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

internal enum class CaptureStage {
    SNAPSHOT,
    FRESH,
    FOLLOW_UP,
}

internal data class CapturePlan(
    val incidentId: String,
    val deviceId: String,
    val keyVersion: Long,
    val expiresAt: Long,
    val nextSequence: Long,
    val stage: CaptureStage,
    val startedAt: Long,
    val nextCaptureAt: Long,
    val lastLatitudeE7: Int?,
    val lastLongitudeE7: Int?,
    val lastQuality: Int,
)

internal data class ArchivedIncident(
    val incidentId: String,
    val expiresAt: Long,
    val archivedAt: Long,
    val result: String = "result_unknown",
)

internal data class StoredState(
    val queue: List<IncidentEvent> = emptyList(),
    val capturePlan: CapturePlan? = null,
    val lastArchive: ArchivedIncident? = null,
)

internal fun validateCapturePlan(plan: CapturePlan) {
    Protocol.validateId(plan.incidentId)
    Protocol.validateId(plan.deviceId)
    require(plan.keyVersion in 1..PROTOCOL_MAX)
    require(plan.expiresAt in 0..PROTOCOL_MAX)
    require(plan.nextSequence in 1..PROTOCOL_MAX)
    require(plan.startedAt in 0..plan.expiresAt)
    require(plan.expiresAt - plan.startedAt in 1..MAX_EVENT_LIFETIME_SECONDS)
    require(plan.nextCaptureAt in 0..PROTOCOL_MAX)
    require(plan.lastQuality in 0..4)
    require((plan.lastLatitudeE7 == null) == (plan.lastLongitudeE7 == null))
    if (plan.lastQuality == 0) {
        require(plan.lastLatitudeE7 == null)
    } else {
        require(plan.lastLatitudeE7 != null)
    }
}

internal fun validateArchivedIncident(archive: ArchivedIncident) {
    Protocol.validateId(archive.incidentId)
    require(archive.expiresAt in 1..PROTOCOL_MAX)
    require(archive.archivedAt in archive.expiresAt..PROTOCOL_MAX)
    require(archive.result == "result_unknown")
}

internal fun stateAfterVerifiedTerminal(
    state: StoredState,
    incidentId: String,
): StoredState? {
    val matchingPlan = state.capturePlan?.incidentId == incidentId
    val matchingEvents = state.queue.any { it.incidentId == incidentId }
    if (!matchingPlan && !matchingEvents) return null
    return state.copy(
        queue = state.queue.filterNot { it.incidentId == incidentId },
        capturePlan = if (matchingPlan) null else state.capturePlan,
    )
}

internal fun stateAfterExpiredLocationScrub(
    state: StoredState,
    now: Long,
): StoredState? {
    val plan = state.capturePlan ?: return null
    if (now < plan.expiresAt) return null
    if (plan.lastLatitudeE7 == null && plan.lastLongitudeE7 == null && plan.lastQuality == 0) {
        return null
    }
    return state.copy(capturePlan = plan.copy(
        lastLatitudeE7 = null,
        lastLongitudeE7 = null,
        lastQuality = 0,
    ))
}

internal class EventStore private constructor(context: Context) {
    private val directory = context.filesDir.resolve("incident-v2")
    private val file = directory.resolve("queue.bin")
    private val atomic = AtomicFile(file)
    private var state: StoredState

    init {
        require(directory.exists() || directory.mkdirs()) { "Cannot create event store" }
        state = read()
    }

    @Synchronized
    fun snapshot(): StoredState = state.copy(queue = state.queue.toList())

    @Synchronized
    fun startIncident(event: IncidentEvent) {
        require(state.queue.isEmpty() && state.capturePlan == null) { "An incident is already pending" }
        require(event.kind == "live.triggered")
        val next = StoredState(
            queue = listOf(event),
            capturePlan = CapturePlan(
                incidentId = event.incidentId,
                deviceId = event.deviceId,
                keyVersion = event.payload.keyVersion,
                expiresAt = event.expiresAt,
                nextSequence = 1,
                stage = CaptureStage.SNAPSHOT,
                startedAt = event.createdAt,
                nextCaptureAt = event.createdAt,
                lastLatitudeE7 = null,
                lastLongitudeE7 = null,
                lastQuality = 0,
            ),
            lastArchive = state.lastArchive,
        )
        replace(next)
    }

    @Synchronized
    fun appendSnapshot(event: IncidentEvent, point: LocationPoint) {
        val plan = requireNotNull(state.capturePlan)
        require(plan.stage == CaptureStage.SNAPSHOT)
        requireMatchesPlan(event, plan)
        val next = state.copy(
            queue = state.queue + event,
            capturePlan = plan.withPoint(point).copy(
                nextSequence = Math.addExact(plan.nextSequence, 1),
                stage = CaptureStage.FRESH,
            ),
        )
        replace(next)
    }

    @Synchronized
    fun appendFresh(event: IncidentEvent, point: LocationPoint, nextCaptureAt: Long) {
        val plan = requireNotNull(state.capturePlan)
        require(plan.stage == CaptureStage.FRESH)
        requireMatchesPlan(event, plan)
        replace(state.copy(
            queue = state.queue + event,
            capturePlan = plan.withPoint(point).copy(
                nextSequence = Math.addExact(plan.nextSequence, 1),
                stage = CaptureStage.FOLLOW_UP,
                nextCaptureAt = nextCaptureAt,
            ),
        ))
    }

    @Synchronized
    fun appendFollowUp(event: IncidentEvent, point: LocationPoint, nextCaptureAt: Long) {
        val plan = requireNotNull(state.capturePlan)
        require(plan.stage == CaptureStage.FOLLOW_UP)
        requireMatchesPlan(event, plan)
        replace(state.copy(
            queue = state.queue + event,
            capturePlan = plan.withPoint(point).copy(
                nextSequence = Math.addExact(plan.nextSequence, 1),
                nextCaptureAt = nextCaptureAt,
            ),
        ))
    }

    @Synchronized
    fun rescheduleFollowUp(nextCaptureAt: Long) {
        val plan = requireNotNull(state.capturePlan)
        require(plan.stage == CaptureStage.FOLLOW_UP)
        replace(state.copy(capturePlan = plan.copy(nextCaptureAt = nextCaptureAt)))
    }

    @Synchronized
    fun archiveVerifiedTerminalIncident(incidentId: String): Boolean {
        val next = stateAfterVerifiedTerminal(state, incidentId) ?: return false
        replace(next)
        return true
    }

    @Synchronized
    fun scrubExpiredLocation(now: Long): Boolean {
        val next = stateAfterExpiredLocationScrub(state, now) ?: return false
        replace(next)
        return true
    }

    @Synchronized
    fun hasExpiredPending(now: Long): Boolean =
        pendingExpiry()?.let { now >= it } ?: false

    @Synchronized
    fun archiveExpired(now: Long): ArchivedIncident {
        val expiresAt = requireNotNull(pendingExpiry()) { "No incident is pending" }
        require(now >= expiresAt) { "An unexpired incident cannot be archived" }
        val incidentId = state.queue.firstOrNull()?.incidentId
            ?: requireNotNull(state.capturePlan).incidentId
        val archive = ArchivedIncident(incidentId, expiresAt, now)
        replace(StoredState(lastArchive = archive))
        return archive
    }

    @Synchronized
    fun removeMatchingHead(eventId: String): Boolean {
        val head = state.queue.firstOrNull() ?: return false
        if (head.eventId != eventId) return false
        replace(state.copy(queue = state.queue.drop(1)))
        return true
    }

    private fun requireMatchesPlan(event: IncidentEvent, plan: CapturePlan) {
        require(state.queue.size < MAX_EVENTS) { "Event queue is full" }
        require(event.kind == "location.updated")
        require(event.incidentId == plan.incidentId)
        require(event.deviceId == plan.deviceId)
        require(event.payload.keyVersion == plan.keyVersion)
        require(event.expiresAt == plan.expiresAt)
        require(event.sequence == plan.nextSequence)
    }

    private fun CapturePlan.withPoint(point: LocationPoint): CapturePlan {
        if (point.quality == 0) return this
        return copy(
            lastLatitudeE7 = point.latitudeE7,
            lastLongitudeE7 = point.longitudeE7,
            lastQuality = point.quality,
        )
    }

    private fun pendingExpiry(): Long? =
        state.queue.firstOrNull()?.expiresAt ?: state.capturePlan?.expiresAt

    private fun replace(next: StoredState) {
        validate(next)
        write(next)
        state = next
    }

    private fun validate(value: StoredState) {
        require(value.queue.size <= MAX_EVENTS)
        value.queue.forEach(Protocol::validateStored)
        value.queue.firstOrNull()?.let { first ->
            value.queue.forEach { event ->
                require(event.incidentId == first.incidentId && event.expiresAt == first.expiresAt)
            }
        }
        value.capturePlan?.let { plan ->
            validateCapturePlan(plan)
            value.queue.forEach { event ->
                require(event.incidentId == plan.incidentId && event.expiresAt == plan.expiresAt)
            }
        }
        value.lastArchive?.let(::validateArchivedIncident)
    }

    private fun read(): StoredState {
        val bytes = try {
            atomic.openRead().use { input ->
                val bounded = ByteArray((MAX_FILE_BYTES + 1).toInt())
                var count = 0
                while (count < bounded.size) {
                    val read = input.read(bounded, count, bounded.size - count)
                    if (read < 0) break
                    count += read
                }
                bounded.copyOf(count)
            }
        } catch (_: FileNotFoundException) {
            return StoredState()
        }
        if (bytes.size.toLong() !in 1..MAX_FILE_BYTES) throw IOException("Event store has invalid size")
        DataInputStream(BufferedInputStream(ByteArrayInputStream(bytes))).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                throw IOException("Unsupported event store")
            }
            val count = input.readInt()
            if (count !in 0..MAX_EVENTS) throw IOException("Invalid event count")
            val queue = ArrayList<IncidentEvent>(count)
            repeat(count) { queue += readEvent(input) }
            val plan = if (input.readBoolean()) {
                val stage = when (input.readUnsignedByte()) {
                    0 -> CaptureStage.SNAPSHOT
                    1 -> CaptureStage.FRESH
                    2 -> CaptureStage.FOLLOW_UP
                    else -> throw IOException("Invalid capture stage")
                }
                CapturePlan(
                    incidentId = input.readUTF(),
                    deviceId = input.readUTF(),
                    keyVersion = input.readLong(),
                    expiresAt = input.readLong(),
                    nextSequence = input.readLong(),
                    stage = stage,
                    startedAt = input.readLong(),
                    nextCaptureAt = input.readLong(),
                    lastLatitudeE7 = if (input.readBoolean()) input.readInt() else null,
                    lastLongitudeE7 = if (input.readBoolean()) input.readInt() else null,
                    lastQuality = input.readUnsignedByte(),
                )
            } else {
                null
            }
            val archive = if (input.readBoolean()) {
                ArchivedIncident(
                    incidentId = input.readUTF(),
                    expiresAt = input.readLong(),
                    archivedAt = input.readLong(),
                    result = input.readUTF(),
                )
            } else {
                null
            }
            if (input.read() != -1) throw IOException("Trailing event-store data")
            return StoredState(queue, plan, archive).also(::validate)
        }
    }

    private fun write(value: StoredState) {
        var output: FileOutputStream? = null
        try {
            val stream = atomic.startWrite()
            output = stream
            val data = DataOutputStream(BufferedOutputStream(stream))
            data.writeInt(MAGIC)
            data.writeInt(FORMAT_VERSION)
            data.writeInt(value.queue.size)
            value.queue.forEach { writeEvent(data, it) }
            val plan = value.capturePlan
            data.writeBoolean(plan != null)
            if (plan != null) {
                data.writeByte(when (plan.stage) {
                    CaptureStage.SNAPSHOT -> 0
                    CaptureStage.FRESH -> 1
                    CaptureStage.FOLLOW_UP -> 2
                })
                data.writeUTF(plan.incidentId)
                data.writeUTF(plan.deviceId)
                data.writeLong(plan.keyVersion)
                data.writeLong(plan.expiresAt)
                data.writeLong(plan.nextSequence)
                data.writeLong(plan.startedAt)
                data.writeLong(plan.nextCaptureAt)
                data.writeBoolean(plan.lastLatitudeE7 != null)
                plan.lastLatitudeE7?.let(data::writeInt)
                data.writeBoolean(plan.lastLongitudeE7 != null)
                plan.lastLongitudeE7?.let(data::writeInt)
                data.writeByte(plan.lastQuality)
            }
            val archive = value.lastArchive
            data.writeBoolean(archive != null)
            if (archive != null) {
                data.writeUTF(archive.incidentId)
                data.writeLong(archive.expiresAt)
                data.writeLong(archive.archivedAt)
                data.writeUTF(archive.result)
            }
            data.flush()
            atomic.finishWrite(stream)
            output = null
        } catch (error: Exception) {
            output?.let(atomic::failWrite)
            throw error
        }
    }

    private fun writeEvent(output: DataOutputStream, event: IncidentEvent) {
        output.writeUTF(event.eventId)
        output.writeUTF(event.incidentId)
        output.writeUTF(event.deviceId)
        output.writeUTF(event.kind)
        output.writeLong(event.sequence)
        output.writeLong(event.createdAt)
        output.writeLong(event.expiresAt)
        output.writeLong(event.payload.keyVersion)
        output.writeUTF(event.payload.iv)
        output.writeUTF(event.payload.ciphertext)
        output.writeUTF(event.payload.tag)
        output.writeUTF(event.requestSignature)
    }

    private fun readEvent(input: DataInputStream): IncidentEvent = IncidentEvent(
        eventId = input.readUTF(),
        incidentId = input.readUTF(),
        deviceId = input.readUTF(),
        kind = input.readUTF(),
        sequence = input.readLong(),
        createdAt = input.readLong(),
        expiresAt = input.readLong(),
        payload = EncryptedPayload(
            keyVersion = input.readLong(),
            iv = input.readUTF(),
            ciphertext = input.readUTF(),
            tag = input.readUTF(),
        ),
        requestSignature = input.readUTF(),
    )

    companion object {
        @Volatile
        private var instance: EventStore? = null

        fun get(context: Context): EventStore = instance ?: synchronized(this) {
            instance ?: EventStore(context.applicationContext).also { instance = it }
        }

        private const val MAGIC = 0x53504232
        private const val FORMAT_VERSION = 2
        private const val MAX_EVENTS = 64
        private const val MAX_FILE_BYTES = 65_536L
    }
}
