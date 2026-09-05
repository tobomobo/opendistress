// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import dev.opendistress.shared.DirectConfig
import org.junit.Assert.*
import org.junit.Test

class PreparationEvidenceTest {
    private val config = DirectConfig(42,
        "https://tenant.grafana.net/oncall/integrations/v1/formatted_webhook/" + "A".repeat(32) + "/",
        "A".repeat(30), "B".repeat(30), "", "", "", "", "", "", "", "")

    @Test fun evidenceDoesNotCrossProviderOrPlatform() {
        val rows = PreparationEvidence.forSetup(config, WatchTarget.GARMIN, listOf(
            DrillEvidence(42, "Garmin", "Grafana", 100),
            DrillEvidence(42, "Wear OS", "Pushover", 100)), 110)
        assertEquals(2, rows.size)
        assertTrue(rows[0].status.startsWith("Owner recorded"))
        assertNull(rows[1].recordedAt)
        assertTrue(rows[1].status.contains("no physical drill"))
    }

    @Test fun configChangeAgeAndClockRollbackNeverLookCurrent() {
        val drill = listOf(DrillEvidence(42, "Garmin", "Grafana", 100))
        fun row(revision: Long, now: Long) = PreparationEvidence.forSetup(config.copy(revision = revision),
            WatchTarget.GARMIN, drill, now).first()
        assertTrue(row(43, 110).status.contains("setup changed"))
        assertTrue(row(42, 99).status.contains("Clock changed"))
        assertTrue(row(42, 100 + 30L * 86400).status.contains("older than 30 days"))
        assertEquals(100L, row(43, 110).recordedAt)
    }

    @Test fun noSavedSetupHasNoReadinessEvidence() {
        assertTrue(PreparationEvidence.forSetup(null, WatchTarget.GARMIN, emptyList(), 100).isEmpty())
        assertTrue(PreparationEvidence.forSetup(config, null, emptyList(), 100).isEmpty())
    }
}
