// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminWatchAppTest {
    @Test fun onlyARecordedDifferentVersionCountsAsAnUpdate() {
        assertFalse(GarminWatchApp.changed(null, 3))
        assertFalse(GarminWatchApp.changed(0, 3))
        assertFalse(GarminWatchApp.changed(3, 3))
        assertFalse(GarminWatchApp.changed(3, 0))
        assertTrue(GarminWatchApp.changed(3, 4))
    }

    @Test fun summaryNamesTheReportedVersion() {
        assertEquals("Watch app: OpenDistress TEST · version 7",
            GarminWatchApp("fēnix 8", "id", "OpenDistress TEST", 7).summary())
        assertEquals("Watch app: OpenDistress · version 2", GarminWatchApp("w", "id", "", 2).summary())
    }
}
