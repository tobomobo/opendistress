// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SecureProvisioningStore private constructor(context: Context) {
    private val atomicFile = AtomicFile(context.filesDir.resolve("phone-provisioning-v1.bin"))
    private var state = read()

    @Synchronized
    fun snapshot(): ProvisioningState = state

    @Synchronized
    fun replace(next: ProvisioningState) {
        val plaintext = ProvisioningStateCodec.encode(next)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ciphertext = cipher.doFinal(plaintext)
        val stream = atomicFile.startWrite()
        try {
            val data = DataOutputStream(stream)
            data.writeInt(FILE_MAGIC)
            data.writeInt(FILE_VERSION)
            data.writeInt(cipher.iv.size)
            data.write(cipher.iv)
            data.writeInt(ciphertext.size)
            data.write(ciphertext)
            data.flush()
            atomicFile.finishWrite(stream)
            state = next
        } catch (error: Exception) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun read(): ProvisioningState = try {
        val bytes = atomicFile.openRead().use { it.readBytes() }
        val input = ByteArrayInputStream(bytes)
        DataInputStream(input).use { data ->
            require(data.readInt() == FILE_MAGIC)
            require(data.readInt() == FILE_VERSION)
            val ivSize = data.readInt()
            require(ivSize == 12)
            val iv = ByteArray(ivSize).also(data::readFully)
            val ciphertextSize = data.readInt()
            require(ciphertextSize in 17..MAX_CIPHERTEXT_BYTES)
            val ciphertext = ByteArray(ciphertextSize).also(data::readFully)
            require(input.available() == 0)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            }
            ProvisioningStateCodec.decode(cipher.doFinal(ciphertext))
        }
    } catch (_: FileNotFoundException) {
        ProvisioningState()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val KEY_ALIAS = "opendistress-phone-provisioning-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FILE_MAGIC = 0x4f445346 // ODSF
        private const val FILE_VERSION = 1
        private const val MAX_CIPHERTEXT_BYTES = 16_384
        @Volatile private var instance: SecureProvisioningStore? = null

        fun get(context: Context): SecureProvisioningStore = instance ?: synchronized(this) {
            instance ?: SecureProvisioningStore(context.applicationContext).also { instance = it }
        }
    }
}
