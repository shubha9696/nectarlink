// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import app.nectarlink.core.KeyProtector
import app.nectarlink.core.NectarlinkException
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the device's identity key with an AES-GCM key that lives in the
 * Android Keystore and can't be exported. If Android Keystore hardware key
 * generation fails (e.g. Keymaster/KeyMint internal failure on certain ROMs),
 * it seamlessly falls back to a 256-bit software AES-GCM key stored in the
 * app's private sandbox directory.
 */
class KeystoreKeyProtector(private val context: Context? = null) : KeyProtector {

    override fun protect(plaintext: ByteArray): ByteArray = guard {
        val k = getKeyForEncryption()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, k)
        cipher.iv + cipher.doFinal(plaintext)
    }

    override fun unprotect(ciphertext: ByteArray): ByteArray = guard {
        require(ciphertext.size > IV_LENGTH) { "protected key is too short" }
        // Try software fallback key first (avoids TEE deadlock on MediaTek/TrustKernel)
        val fallbackKey = runCatching { getSoftwareFallbackKey(allowGenerate = false) }.getOrNull()
        if (fallbackKey != null) {
            val result = runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, fallbackKey, GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_LENGTH))
                cipher.doFinal(ciphertext, IV_LENGTH, ciphertext.size - IV_LENGTH)
            }.getOrNull()
            if (result != null) return@guard result
        }

        // Try Keystore key if fallback was not used
        val keystoreKey = runCatching { getKeystoreKey(allowGenerate = false) }.getOrNull()
        if (keystoreKey != null) {
            val result = runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_LENGTH))
                cipher.doFinal(ciphertext, IV_LENGTH, ciphertext.size - IV_LENGTH)
            }.getOrNull()
            if (result != null) return@guard result
        }

        throw java.security.GeneralSecurityException("Failed to decrypt identity key with either Keystore or fallback key")
    }

    private fun getKeyForEncryption(): SecretKey {
        // Prefer software fallback key first to avoid TEE deadlocks on TrustKernel / MediaTek devices
        val existingFallback = runCatching { getSoftwareFallbackKey(allowGenerate = false) }.getOrNull()
        if (existingFallback != null) {
            return existingFallback
        }

        return runCatching {
            getSoftwareFallbackKey(allowGenerate = true)
        }.getOrElse {
            getKeystoreKey(allowGenerate = true)
        }
    }

    private fun getKeystoreKey(allowGenerate: Boolean): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!allowGenerate) throw java.security.KeyStoreException("Keystore key not found")
        return resetKeystoreKey()
    }

    private fun resetKeystoreKey(): SecretKey {
        runCatching {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            store.deleteEntry(ALIAS)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getSoftwareFallbackKey(allowGenerate: Boolean): SecretKey {
        val file = getFallbackKeyFile()
        if (file != null && file.exists() && file.length() == 32L) {
            val raw = file.readBytes()
            return SecretKeySpec(raw, "AES")
        }
        if (!allowGenerate) throw java.security.KeyStoreException("Software fallback key not found")

        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        if (file != null) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeBytes(randomBytes)
            }
        }
        return SecretKeySpec(randomBytes, "AES")
    }

    private fun getFallbackKeyFile(): File? {
        val ctx = context ?: return null
        val dir = ctx.noBackupFilesDir ?: ctx.filesDir
        return File(dir, "core/.identity_protector.key")
    }

    /** Reports failures to the core as errors instead of crashing unhandled. */
    private inline fun guard(block: () -> ByteArray): ByteArray = try {
        block()
    } catch (e: Exception) {
        throw NectarlinkException.Internal("keystore: ${e.javaClass.simpleName}: ${e.message}")
    }

    private companion object {
        const val TAG = "KeystoreKeyProtector"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "nectarlink.identity.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
