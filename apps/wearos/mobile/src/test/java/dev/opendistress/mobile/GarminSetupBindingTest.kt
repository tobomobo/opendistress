// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminSetupBindingTest {
    @Test fun delayedAndClockSkewedAcknowledgementsStayUnconfirmed() {
        val binding = GarminSetupBinding(100, "test-app", 42, "digest", 1_000)
        assertFalse(binding.isFreshAck(999, 1_005))
        assertFalse(binding.isFreshAck(1_000, 1_005))
        assertFalse(binding.isFreshAck(1_006, 1_005))
        assertTrue(binding.isFreshAck(1_001, 1_005))
    }

    @Test fun confirmationCannotCrossDeviceAppOrConfiguration() {
        val binding = GarminSetupBinding(100, "test-app", 42, "digest")
        assertTrue(binding.matches(100, "test-app", 42, "digest"))
        assertFalse(binding.matches(101, "test-app", 42, "digest"))
        assertFalse(binding.matches(100, "other-app", 42, "digest"))
        assertFalse(binding.matches(100, "test-app", 43, "digest"))
        assertFalse(binding.matches(100, "test-app", 42, "changed"))
    }
}
