// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.opendistress.shared.DirectConfig
import dev.opendistress.shared.ProvisioningCrypto

/** Emulator-only state seeding. This class is absent from release builds. */
class DirectDebugSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CONFIGURE -> configure(context)
            ACTION_ACCEPTED -> accepted(context)
            ACTION_RESET -> reset(context)
        }
    }

    private fun configure(context: Context): DirectConfig {
        val store = EncryptedDirectConfigStore.get(context)
        store.snapshot()?.config?.let { return it }
        val config = DirectConfig(
            revision = System.currentTimeMillis().coerceAtLeast(1),
            grafanaWebhookUrl = null,
            pushoverUserKey = "U".repeat(30),
            pushoverApiToken = "T".repeat(30),
            protectedPersonName = "Emulator Test Person",
            customAlertMessage = "Automated TEST only. No assistance is required.",
            homeAddress = "Emulator fixture; not a real address",
            childrenInfo = "Emulator fixture",
            personDescription = "Emulator fixture",
            backgroundInfo = "Debug build state for interaction testing",
            responseInstructions = "Do not take action; this is a TEST fixture.",
            profilePhotoUrl = "",
        )
        val announcement = store.watchAnnouncement()
        val envelope = ProvisioningCrypto.seal(config, announcement)
        store.install(envelope.canonicalBytes())
        return config
    }

    private fun accepted(context: Context) {
        val config = configure(context)
        val store = DirectTestStore.get(context)
        val state = store.snapshot() ?: store.begin(config, now())
        if (state.acceptedAt != null) return
        val request = state.queue.first { it.kind == DirectRequestKind.TRIGGER }
        store.recordTriggerAccepted(
            request.requestId,
            DirectProviderAcceptance(
                provider = request.provider,
                reference = "R".repeat(30),
                emergencyReceipt = "E".repeat(30),
            ),
            now(),
        )
    }

    private fun reset(context: Context) {
        val store = DirectTestStore.get(context)
        val state = store.snapshot() ?: return
        when {
            state.acceptedAt != null -> store.clearAcceptedTestFixture()
            state.routes.all { it.status == DirectRouteStatus.REJECTED } -> store.resetDefinitivelyRejectedTest()
            now() >= state.triggerExpiresAt -> store.archiveExpiredTest(now())
        }
    }

    private fun now(): Long = System.currentTimeMillis() / 1_000

    companion object {
        const val ACTION_CONFIGURE = "dev.opendistress.wear.debug.CONFIGURE"
        const val ACTION_ACCEPTED = "dev.opendistress.wear.debug.ACCEPTED"
        const val ACTION_RESET = "dev.opendistress.wear.debug.RESET"
    }
}
