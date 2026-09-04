// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.opendistress.shared.DirectConfig
import dev.opendistress.shared.DirectConfigEnvelope
import dev.opendistress.shared.ProvisioningCrypto
import dev.opendistress.shared.WatchKeyAnnouncement
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64

internal interface WatchConfigIdentity {
    fun announcement(): WatchKeyAnnouncement
    fun open(envelope: DirectConfigEnvelope): DirectConfig
}

internal class AndroidKeystoreWatchIdentity : WatchConfigIdentity {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    override fun announcement(): WatchKeyAnnouncement {
        val entry = keyPairEntry()
        val publicKey = entry.certificate.publicKey.encoded
        val stableIdBytes = MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(16)
        val watchId = Base64.getUrlEncoder().withoutPadding().encodeToString(stableIdBytes)
        return WatchKeyAnnouncement(watchId, KEY_VERSION, publicKey)
    }

    override fun open(envelope: DirectConfigEnvelope): DirectConfig {
        val expected = announcement()
        require(envelope.watchId == expected.watchId && envelope.watchKeyVersion == expected.keyVersion) {
            "Direct config targets another watch identity"
        }
        require(MessageDigest.isEqual(envelope.watchPublicKeyDigestSha256, expected.publicKeyDigestSha256())) {
            "Direct config targets another watch key"
        }
        return ProvisioningCrypto.open(envelope, keyPairEntry().privateKey)
    }

    private fun keyPairEntry(): KeyStore.PrivateKeyEntry {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(3072)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generator.generateKeyPair()
        }
        return requireNotNull(keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "opendistress.direct.config.rsa.v1"
        const val KEY_VERSION = 1L
    }
}
