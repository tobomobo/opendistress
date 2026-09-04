// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import java.util.Locale
import kotlin.math.roundToInt

internal enum class DirectLocationSource {
    LAST_KNOWN_FUSED,
    CURRENT_FUSED,
}

internal data class DirectLocationFix(
    val latitude: Double,
    val longitude: Double,
    val capturedAt: Long,
    val accuracyMeters: Float?,
    val source: DirectLocationSource,
)

internal data class FormattedLocationUpdate(
    val sequence: Long,
    val title: String,
    val message: String,
    val mapUrl: String,
    val ageSeconds: Long,
    val quality: String,
    val mayBeStale: Boolean,
    val grafanaFields: Map<String, Any>,
)

internal object DirectLocationFormatter {
    fun format(sequence: Long, sentAt: Long, fix: DirectLocationFix): FormattedLocationUpdate {
        require(sequence >= 1)
        require(sentAt >= 0 && fix.capturedAt in 1..sentAt)
        require(fix.latitude.isFinite() && fix.latitude in -90.0..90.0)
        require(fix.longitude.isFinite() && fix.longitude in -180.0..180.0)
        require(fix.accuracyMeters == null || (fix.accuracyMeters.isFinite() && fix.accuracyMeters >= 0))

        val age = sentAt - fix.capturedAt
        val quality = quality(fix.accuracyMeters)
        val stale = fix.source == DirectLocationSource.LAST_KNOWN_FUSED || age > STALE_AFTER_SECONDS
        val mapUrl = String.format(
            Locale.US,
            "https://maps.google.com/?q=%.7f,%.7f",
            fix.latitude,
            fix.longitude,
        )
        val source = when (fix.source) {
            DirectLocationSource.LAST_KNOWN_FUSED -> "Letzter bekannter Fused-Standort"
            DirectLocationSource.CURRENT_FUSED -> "Aktueller Fused-Standort von Uhr oder verbundenem Handy"
        }
        val status = when {
            fix.source == DirectLocationSource.LAST_KNOWN_FUSED ->
                "WARNUNG: letzter bekannter Standort; moeglicherweise veraltet."
            stale -> "WARNUNG: Standort ist aelter als $STALE_AFTER_SECONDS Sekunden."
            else -> "Aktueller Pixel-Watch-Teststandort."
        }
        val accuracy = fix.accuracyMeters?.let { "${it.roundToInt()} m" } ?: "unbekannt"
        val title = "GPS-UPDATE $sequence — TESTNOTRUF — OPENDISTRESS"
        val message = "GPS-UPDATE $sequence\n\n" +
            "TESTMODUS — KEIN ECHTER NOTFALL\n\n" +
            "GPS-STATUS\n$status\nQuelle: $source\nQualitaet: $quality\nGenauigkeit: $accuracy\n\n" +
            "GPS-ALTER LAUT GERAET\n$age s\n\n" +
            "KARTE\n$mapUrl"
        val fields = linkedMapOf<String, Any>(
            "link_to_upstream_details" to mapUrl,
            "gps_capture_time" to fix.capturedAt,
            "gps_age_seconds" to age,
            "gps_capture_age_unknown" to false,
            "gps_fix_kind" to when (fix.source) {
                DirectLocationSource.LAST_KNOWN_FUSED -> "last_known_fused"
                DirectLocationSource.CURRENT_FUSED -> "current_fused"
            },
            "gps_quality" to quality,
            "gps_accuracy_meters" to (fix.accuracyMeters?.roundToInt() ?: -1),
            "gps_may_be_stale" to stale,
        )
        return FormattedLocationUpdate(sequence, title, message, mapUrl, age, quality, stale, fields)
    }

    private fun quality(accuracy: Float?): String = when {
        accuracy == null -> "unbekannt"
        accuracy <= 20f -> "gut"
        accuracy <= 100f -> "mittel"
        else -> "grob"
    }

    private const val STALE_AFTER_SECONDS = 30L
}
