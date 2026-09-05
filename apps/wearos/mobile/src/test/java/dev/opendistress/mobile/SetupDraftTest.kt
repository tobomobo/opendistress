// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import dev.opendistress.shared.DirectConfig
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SetupDraftTest {
    @Test fun savedBriefingCanBeEditedWithoutDuplicatingWords() {
        val compiled = ResponsePlanTemplates.compile(ResponsePlanTemplates.CALLBACK, "apple river")
        val (words, plan) = ResponsePlanTemplates.split(compiled)
        assertEquals("apple river", words)
        assertEquals(ResponsePlanTemplates.CALLBACK, plan)
        assertEquals(compiled, ResponsePlanTemplates.compile(plan, words))
        assertEquals("" to "Custom plan", ResponsePlanTemplates.split("Custom plan"))
        assertEquals("" to "Expected: unusual custom text", ResponsePlanTemplates.split("Expected: unusual custom text"))
    }
    @Test fun draftRoundTripsWithoutChangingPublishedConfiguration() {
        val config = DirectConfig(1, null, "A".repeat(30), "B".repeat(30), "", "", "", "", "", "", "", "")
        val draft = SetupDraft(mapOf("protectedPersonName" to "Draft only", "responseInstructions" to ResponsePlanTemplates.CALLBACK),
            3, false, "abandon ability", true)
        val saved = ProvisioningState().afterSave(config).afterPublish("watch_1234567890")
        val restored = ProvisioningStateCodec.decode(ProvisioningStateCodec.encode(saved.copy(draft = draft)))
        assertEquals(saved.config, restored.config)
        assertEquals(saved.pending, restored.pending)
        assertNull(restored.confirmed)
        assertEquals(draft.values["protectedPersonName"], restored.draft!!.values["protectedPersonName"])
        assertEquals(3, restored.draft.step)
        assertFalse(restored.draft.haptics)
        assertEquals("abandon ability", restored.draft.words)
        assertTrue(restored.draft.wordsLearned)
        assertArrayEquals(config.canonicalBytes(), restored.config!!.canonicalBytes())
        assertEquals(GarminCompanionProtocol.configMessage(config), GarminCompanionProtocol.configMessage(restored.config))
        assertFalse(String(restored.config.canonicalBytes()).contains("abandon ability"))
        assertFalse(GarminCompanionProtocol.configMessage(restored.config).values.any { it.toString().contains("abandon ability") })
    }

    @Test fun versionTwoStillLoadsWithoutInventingAnAgreement() {
        val current = ProvisioningStateCodec.encode(ProvisioningState())
        val old = current.copyOf(current.size - 4)
        java.nio.ByteBuffer.wrap(old).putInt(4, 2)
        assertNull(ProvisioningStateCodec.decode(old).draft)
    }

    @Test fun invalidOrOversizedDraftsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SetupDraft(step = 6) }
        assertThrows(IllegalArgumentException::class.java) { SetupDraft(wordsLearned = true) }
        assertThrows(IllegalArgumentException::class.java) { SetupDraft(values = mapOf("unexpected" to "value")) }
        assertThrows(IllegalArgumentException::class.java) { SetupDraft(values = mapOf("responseInstructions" to "a".repeat(181))) }
        assertThrows(IllegalArgumentException::class.java) { SetupDraft.decode(SetupDraft().encode() + 0) }
        val largest = SetupDraft(SetupDraft.limits.mapValues { "界".repeat(it.value) })
        assertTrue(largest.encode().size < 8192)
        assertEquals(largest.values, SetupDraft.decode(largest.encode()).values)
    }

    @Test fun templatesCompileCompleteWordsAndInstructionsWithinTheExistingWatchField() {
        for (template in listOf(ResponsePlanTemplates.QUIET, ResponsePlanTemplates.CALLBACK)) {
            assertTrue(template.length <= 180)
            assertFalse(template.contains("abandon ability"))
        }
        assertTrue(ResponsePlanTemplates.CALLBACK.contains("do not prove safety"))
        val words = "abstract strategy"
        val briefing = ResponsePlanTemplates.compile(ResponsePlanTemplates.CALLBACK, words)
        assertTrue(briefing.length <= 180)
        assertTrue(briefing.contains("Expected: $words"))
        assertTrue(briefing.contains("No reply, wrong words or doubt: call police"))
        assertTrue(briefing.contains("last known location"))
        assertTrue(briefing.endsWith("Words do not prove safety."))
        val config = DirectConfig(1, null, "A".repeat(30), "B".repeat(30), "", "", "", "", "", "", briefing, "")
        assertEquals(briefing, GarminCompanionProtocol.configMessage(config)["responseInstructions"])
        assertEquals(briefing, DirectConfig.fromCanonicalBytes(config.canonicalBytes()).responseInstructions)
        assertEquals("Custom response", ResponsePlanTemplates.compile("Custom response", ""))
        assertThrows(IllegalArgumentException::class.java) { ResponsePlanTemplates.compile(ResponsePlanTemplates.CALLBACK, "") }
        assertThrows(IllegalArgumentException::class.java) { ResponsePlanTemplates.compile("a".repeat(180), words) }
    }

    @Test fun oldPrivateAgreementNeverGrantsNewTransmissionApproval() {
        val old = SetupDraft(words = "abandon ability", wordsLearned = true).encode()
        java.nio.ByteBuffer.wrap(old).putInt(0, 1)
        val migrated = SetupDraft.decode(old)
        assertEquals("abandon ability", migrated.words)
        assertFalse(migrated.wordsLearned)
    }

    @Test fun vocabularyIsTheUnmodifiedBip39ListAndPairsAreDistinct() {
        val bytes = File("src/main/assets/bip39-english.txt").readBytes()
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val vocabulary = bytes.toString(Charsets.UTF_8).trim().lines()
        repeat(100) {
            val pair = ConversationWords.generate(vocabulary).split(" ")
            assertEquals(2, pair.size)
            assertNotEquals(pair[0], pair[1])
            assertTrue(pair.all { it in vocabulary })
        }
        assertThrows(IllegalArgumentException::class.java) { ConversationWords.generate(listOf("wrong")) }
    }
}
