// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectTrackingScheduleTest {
    @Test
    fun cadenceContinuesForLongIncidentsWithoutAOneHourCutoff() {
        assertEquals(30L, directTrackingCadenceSeconds(1_000, 1_000, false))
        assertEquals(30L, directTrackingCadenceSeconds(1_000, 1_299, false))
        assertEquals(120L, directTrackingCadenceSeconds(1_000, 1_300, false))
        assertEquals(120L, directTrackingCadenceSeconds(1_000, 2_799, false))
        assertEquals(300L, directTrackingCadenceSeconds(1_000, 2_800, false))
        assertEquals(300L, directTrackingCadenceSeconds(1_000, 20_000, false))
    }

    @Test
    fun lowBatteryDoublesCadenceInsteadOfStoppingTracking() {
        assertEquals(60L, directTrackingCadenceSeconds(1_000, 1_000, true))
        assertEquals(240L, directTrackingCadenceSeconds(1_000, 1_300, true))
        assertEquals(600L, directTrackingCadenceSeconds(1_000, 2_800, true))
    }

    @Test
    fun productionLocationValidityRejectsMockFutureAndOutOfRangeFixes() {
        assertTrue(isUsableDirectLocation(48.2, 16.3, 1_000, 1_005, false))
        assertFalse(isUsableDirectLocation(48.2, 16.3, 1_000, 1_005, true))
        assertFalse(isUsableDirectLocation(91.0, 16.3, 1_000, 1_005, false))
        assertFalse(isUsableDirectLocation(48.2, 16.3, 1_006, 1_005, false))
    }
}
