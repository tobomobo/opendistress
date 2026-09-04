// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom

/** Unpublished preparation. Only the reviewed response briefing is compiled into watch settings. */
internal class SetupDraft(
    val values: Map<String, String> = emptyMap(),
    val step: Int = 0,
    val haptics: Boolean = true,
    val words: String = "",
    val wordsLearned: Boolean = false,
) {
    init {
        require(step in 0..5)
        require(values.all { (key, value) -> limits[key]?.let { value.length <= it } == true })
        require(words.isEmpty() || words.matches(Regex("[a-z]{3,8} [a-z]{3,8}")))
        require(!wordsLearned || words.isNotEmpty())
    }

    fun encode(): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(2)
            data.writeInt(step)
            data.writeBoolean(haptics)
            data.writeUTF(words)
            data.writeBoolean(wordsLearned)
            limits.keys.forEach { data.writeUTF(values[it].orEmpty()) }
        }
    }.toByteArray()

    companion object {
        val limits = linkedMapOf(
            "grafanaWebhookUrl" to 512, "pushoverUserKey" to 30, "pushoverApiToken" to 30,
            "protectedPersonName" to 40, "customAlertMessage" to 240, "homeAddress" to 120,
            "childrenInfo" to 150, "personDescription" to 150, "backgroundInfo" to 180,
            "responseInstructions" to 180, "profilePhotoUrl" to 512,
        )

        fun decode(bytes: ByteArray): SetupDraft {
            require(bytes.size <= 8192)
            val raw = ByteArrayInputStream(bytes)
            return DataInputStream(raw).use { data ->
                val version = data.readInt()
                require(version in 1..2)
                val step = data.readInt()
                val haptics = data.readBoolean()
                val words = data.readUTF()
                val agreed = data.readBoolean()
                val values = limits.keys.associateWith { data.readUTF() }
                require(raw.available() == 0)
                // The old checkbox meant shared out-of-band, not learned or approved for transmission.
                SetupDraft(values, step, haptics, words, version >= 2 && agreed)
            }
        }
    }
}

internal object ConversationWords {
    fun generate(vocabulary: List<String>, random: SecureRandom = SecureRandom()): String {
        require(vocabulary.size == 2048 && vocabulary.toSet().size == 2048)
        require(vocabulary.all { it.matches(Regex("[a-z]{3,8}")) })
        val first = random.nextInt(vocabulary.size)
        val offset = 1 + random.nextInt(vocabulary.size - 1)
        return "${vocabulary[first]} ${vocabulary[(first + offset) % vocabulary.size]}"
    }
}

internal object ResponsePlanTemplates {
    const val QUIET = "Do not call me: ringing may put me at risk. Contact police and share this alert and last known location. No reply is not an all-clear."
    const val CALLBACK = "Call me; ask for both words, do not read them out.\nNo reply, wrong words or doubt: call police with last known location.\nWords do not prove safety."

    fun compile(plan: String, words: String): String {
        require(plan.isNotBlank()) { "Enter a response plan first" }
        require(plan != CALLBACK || words.isNotEmpty()) { "Generate and learn your callback words first" }
        val briefing = if (words.isEmpty()) plan else "Expected: $words\n\n$plan"
        require(briefing.length <= 180) {
            "Briefing is ${briefing.length}/180 characters. Shorten the plan to ${planBudget(words)} characters; the words must not be cut off."
        }
        return briefing
    }

    fun planBudget(words: String): Int = 180 - if (words.isEmpty()) 0 else "Expected: $words\n\n".length
}
