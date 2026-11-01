// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.feature.unlock

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Bridge interface implemented by `core/nectarlink-ffi` (UniFFI) to send
 * `EnrollAck`, `UnlockResponse`, or `UnlockDenied` over the QUIC stream.
 */
interface NectarLinkUnlockCoreBridge {
    fun submitEnrollmentAck(vaultId: String, decryptedPhoneKeyShare: ByteArray)
    fun submitUnlockApproval(
        vaultId: String,
        challengeNonce: ByteArray,
        decryptedPhoneKeyShare: ByteArray,
    )
    fun submitUnlockDenied(vaultId: String, challengeNonce: ByteArray, reason: String)
}

/**
 * Represents an incoming `UnlockChallenge` pushed from a paired PC over QUIC.
 */
data class IncomingPcUnlockChallenge(
    val vaultId: String,
    val pcName: String,
    val accountName: String,
    val reasonLabel: String,
    val challengeNonce: ByteArray,
    val expiresAtUnixMs: Long,
)

/**
 * Hardware-backed Android Keystore wrapper for NectarLink PC Unlock.
 *
 * Each enrolled PC vault slot (`vaultId`) gets its own AES-256-GCM key inside
 * `"AndroidKeyStore"` configured with:
 * - `setUserAuthenticationRequired(true)` (every single cipher operation requires
 *   a live hardware biometric verification via `BiometricPrompt.CryptoObject`)
 * - `setInvalidatedByBiometricEnrollment(true)` (enrolling a new fingerprint on
 *   the phone automatically invalidates the PC unlock key to prevent local escalation).
 */
class PcUnlockKeystoreManager(
    private val encryptedPrefs: android.content.SharedPreferences,
) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS_PREFIX = "nectarlink_pc_unlock_v0_"
        private const val GCM_TAG_BITS = 128
    }

    private fun keyAlias(vaultId: String): String = "$KEY_ALIAS_PREFIX$vaultId"

    /**
     * Generates a fresh biometric-gated AES-256-GCM key in the Android Keystore
     * and returns an ENCRYPT_MODE [Cipher] ready to be passed to [BiometricPrompt].
     */
    fun createEnrollmentCipher(vaultId: String): Cipher {
        val alias = keyAlias(vaultId)
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        keyGenerator.init(spec)
        val secretKey = keyGenerator.generateKey()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher
    }

    /**
     * Prepares a DECRYPT_MODE [Cipher] for an existing enrolled `vaultId` so
     * [BiometricPrompt] can authorize decrypting the stored `phone_key_share`.
     *
     * @throws KeyPermanentlyInvalidatedException if the user added/removed a
     *   fingerprint on the device since enrolling the PC.
     */
    fun createUnlockCipher(vaultId: String): Cipher {
        val ivBase64 = encryptedPrefs.getString("iv_$vaultId", null)
            ?: throw IllegalStateException("No enrolled IV for vault $vaultId")
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val secretKey = keyStore.getKey(keyAlias(vaultId), null) as? SecretKey
            ?: throw IllegalStateException("No Keystore key for vault $vaultId")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher
    }

    /**
     * Persists the wrapped `phone_key_share` ciphertext and GCM IV after the user
     * authenticates the enrollment [BiometricPrompt].
     */
    fun finishEnrollment(
        vaultId: String,
        authenticatedCipher: Cipher,
        rawPhoneKeyShare: ByteArray,
    ) {
        val ciphertext = authenticatedCipher.doFinal(rawPhoneKeyShare)
        val iv = authenticatedCipher.iv
        encryptedPrefs.edit()
            .putString("ct_$vaultId", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString("iv_$vaultId", Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
    }

    /**
     * Decrypts and returns the 32-byte `phone_key_share` using the cipher unlocked
     * by [BiometricPrompt]. Caller MUST zeroize the returned array immediately after
     * passing it to `NectarLinkUnlockCoreBridge`.
     */
    fun decryptPhoneKeyShare(vaultId: String, authenticatedCipher: Cipher): ByteArray {
        val ctBase64 = encryptedPrefs.getString("ct_$vaultId", null)
            ?: throw IllegalStateException("No enrolled ciphertext for vault $vaultId")
        val ciphertext = Base64.decode(ctBase64, Base64.NO_WRAP)
        return authenticatedCipher.doFinal(ciphertext)
    }

    fun revokeVault(vaultId: String) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(keyAlias(vaultId))
        encryptedPrefs.edit()
            .remove("ct_$vaultId")
            .remove("iv_$vaultId")
            .apply()
    }
}

/**
 * Coordinates the Android [BiometricPrompt] lifecycle with `CryptoObject` binding
 * and calls back into `nectarlink-ffi` (`NectarLinkUnlockCoreBridge`).
 */
class PcUnlockBiometricCoordinator(
    private val activity: FragmentActivity,
    private val keystoreManager: PcUnlockKeystoreManager,
    private val coreBridge: NectarLinkUnlockCoreBridge,
) {
    /**
     * Prompts the user to scan their fingerprint to seal `phoneKeyShare` in the
     * Android Keystore and send `EnrollAck` back to the PC.
     */
    fun promptToEnroll(
        vaultId: String,
        pcName: String,
        accountName: String,
        phoneKeyShare: ByteArray,
    ) {
        val cipher = keystoreManager.createEnrollmentCipher(vaultId)
        val executor = ContextCompat.getMainExecutor(activity)

        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    val authedCipher = result.cryptoObject?.cipher ?: return
                    try {
                        keystoreManager.finishEnrollment(vaultId, authedCipher, phoneKeyShare)
                        coreBridge.submitEnrollmentAck(vaultId, phoneKeyShare)
                    } finally {
                        phoneKeyShare.fill(0)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    phoneKeyShare.fill(0)
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Enable PC Unlock for $pcName")
            .setSubtitle("Account: $accountName")
            .setDescription("Scan your fingerprint to bind this PC to your phone's secure enclave.")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()

        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /**
     * Prompts the user to scan their fingerprint to approve an incoming
     * [IncomingPcUnlockChallenge] from the PC lock screen or `sudo`/UAC prompt.
     */
    fun promptToUnlock(challenge: IncomingPcUnlockChallenge) {
        val cipher = try {
            keystoreManager.createUnlockCipher(challenge.vaultId)
        } catch (e: KeyPermanentlyInvalidatedException) {
            keystoreManager.revokeVault(challenge.vaultId)
            coreBridge.submitUnlockDenied(
                challenge.vaultId,
                challenge.challengeNonce,
                "Biometric enrollment changed on phone; please re-enroll PC unlock.",
            )
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    val authedCipher = result.cryptoObject?.cipher ?: return
                    val share = keystoreManager.decryptPhoneKeyShare(
                        challenge.vaultId,
                        authedCipher,
                    )
                    try {
                        coreBridge.submitUnlockApproval(
                            challenge.vaultId,
                            challenge.challengeNonce,
                            share,
                        )
                    } finally {
                        share.fill(0)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    coreBridge.submitUnlockDenied(
                        challenge.vaultId,
                        challenge.challengeNonce,
                        errString.toString(),
                    )
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock ${challenge.pcName}")
            .setSubtitle("${challenge.accountName} • ${challenge.reasonLabel}")
            .setDescription("Touch the fingerprint sensor to unlock your PC over NectarLink.")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Deny")
            .build()

        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}

/**
 * Jetpack Compose bottom sheet displayed in the Android app when a paired PC
 * wakes up or requests privilege elevation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcUnlockChallengeSheet(
    challenge: IncomingPcUnlockChallenge,
    onScanFingerprint: () -> Unit,
    onDeny: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDeny) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = challenge.reasonLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Unlock ${challenge.pcName}?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Account: ${challenge.accountName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Deny")
                }
                Button(
                    onClick = onScanFingerprint,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Scan Fingerprint")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}