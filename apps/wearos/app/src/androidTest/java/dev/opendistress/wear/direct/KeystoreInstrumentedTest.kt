// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opendistress.shared.DirectConfig
import dev.opendistress.shared.ProvisioningCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreInstrumentedTest {
    @Test
    fun directStateCipherRoundTripsWithWearOsKeystoreNoncePolicy() {
        val cipher = AndroidKeystoreDirectStateCipher()
        val plaintext = "emulator-keystore-round-trip".toByteArray()
        assertArrayEquals(plaintext, cipher.decrypt(cipher.encrypt(plaintext)))
    }

    @Test
    fun watchIdentityOpensOnlyItsEncryptedPhoneConfiguration() {
        val identity = AndroidKeystoreWatchIdentity()
        val config = DirectConfig(
            revision = 1,
            grafanaWebhookUrl = null,
            pushoverUserKey = "U".repeat(30),
            pushoverApiToken = "T".repeat(30),
            protectedPersonName = "Test Person",
            customAlertMessage = "TEST only",
            homeAddress = "",
            childrenInfo = "",
            personDescription = "",
            backgroundInfo = "",
            responseInstructions = "",
            profilePhotoUrl = "",
        )
        val envelope = ProvisioningCrypto.seal(config, identity.announcement())
        assertEquals(config, identity.open(envelope))
    }
}
