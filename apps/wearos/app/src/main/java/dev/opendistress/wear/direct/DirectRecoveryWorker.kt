// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.core.app.NotificationCompat
import dev.opendistress.wear.MainActivity
import dev.opendistress.wear.R
import java.util.concurrent.TimeUnit

/**
 * OS-managed safety net for already-durable provider requests.
 *
 * WorkManager persists this work across process death and reboot. The foreground
 * service remains the fast path; this worker only replays the exact committed
 * request and never creates an incident or captures location on its own.
 */
class DirectRecoveryWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val store = runCatching { DirectTestStore.get(applicationContext) }.getOrElse {
            return Result.failure()
        }
        val config = runCatching {
            EncryptedDirectConfigStore.get(applicationContext).snapshot()?.config
        }.getOrNull() ?: return Result.success()
        val state = store.snapshot() ?: return Result.success()
        val currentNow = now()
        if (state.isResetPending() && currentNow >= state.pushoverEmergencyRepeatsUntil()) {
            return runCatching {
                store.completeResetAfterEmergencyExpiry(currentNow)
                Result.success()
            }.getOrElse { Result.retry() }
        }
        val request = store.nextRequest(config, currentNow) ?: return Result.success()
        if (!DirectNetworkGate.sending.compareAndSet(false, true)) return Result.retry()
        return try {
            val outcome = DirectProviderTransport().send(request)
            when {
                outcome.acceptance != null && request.kind == DirectRequestKind.TRIGGER -> {
                    store.recordTriggerAccepted(request.requestId, outcome.acceptance, now())
                    vibrateAccepted()
                    if (!DirectNetworkGate.serviceActive.get()) notifyOpenForGps()
                }
                outcome.acceptance != null && request.kind == DirectRequestKind.LOCATION ->
                    store.recordLocationAccepted(request.requestId)
                outcome.acceptance != null && request.kind == DirectRequestKind.CANCEL -> {
                    store.recordCancellationAccepted(request.requestId, outcome.acceptance)
                    return Result.success()
                }
                request.kind == DirectRequestKind.CANCEL && !outcome.retryable ->
                    return Result.failure()
                outcome.retryable -> {
                    store.recordRetryableAttempt(request.requestId)
                    return Result.retry()
                }
                else -> store.recordDefiniteRejection(request.requestId)
            }
            val current = store.snapshot() ?: return Result.success()
            if (store.nextRequest(config, now()) != null && current.incidentId == state.incidentId) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        } finally {
            DirectNetworkGate.sending.set(false)
        }
    }

    private fun now(): Long = System.currentTimeMillis() / 1_000

    private fun notifyOpenForGps() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                RECOVERY_CHANNEL_ID,
                "OpenDistress recovery",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Asks you to reopen an accepted TEST when Android blocks background GPS restart"
            },
        )
        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            RECOVERY_NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, RECOVERY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_status)
                .setContentTitle("OpenDistress TEST accepted")
                .setContentText("Open the app to resume high-rate GPS updates")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    private fun vibrateAccepted() {
        applicationContext.getSystemService(Vibrator::class.java)
            ?.takeIf(Vibrator::hasVibrator)
            ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
    }

    private companion object {
        const val RECOVERY_CHANNEL_ID = "opendistress-recovery"
        const val RECOVERY_NOTIFICATION_ID = 8_202
    }
}

internal object DirectRecoveryWork {
    private const val UNIQUE_NAME = "opendistress-direct-provider-recovery"

    fun ensure(context: Context) {
        val request = OneTimeWorkRequestBuilder<DirectRecoveryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
    }
}
