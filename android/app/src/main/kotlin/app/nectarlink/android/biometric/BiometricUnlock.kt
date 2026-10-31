// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.biometric

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import android.widget.Toast
import app.nectarlink.android.core.Core

object BiometricUnlock {
    fun unlock(
        context: Context,
        pcId: String,
        core: Core,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        if (context !is Activity) {
            val intent = Intent(context, app.nectarlink.android.ui.BiometricUnlockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signal = CancellationSignal()
            try {
                val prompt = BiometricPrompt.Builder(context)
                    .setTitle("Unlock PC")
                    .setSubtitle("Touch the fingerprint sensor to unlock your PC")
                    .setNegativeButton("Cancel", context.mainExecutor) { _, _ ->
                        signal.cancel()
                    }
                    .build()

                prompt.authenticate(
                    signal,
                    context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            core.pcUnlock(pcId)
                            app.nectarlink.android.core.PcLockManager.setPcLocked(false)
                            Toast.makeText(context, "PC Unlocked via Fingerprint", Toast.LENGTH_SHORT).show()
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED &&
                                errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                            ) {
                                Toast.makeText(context, errString ?: "Authentication failed", Toast.LENGTH_SHORT).show()
                            }
                            onError(errString?.toString() ?: "Authentication failed")
                        }
                    },
                )
            } catch (e: Exception) {
                Log.e("BiometricUnlock", "Failed to start biometric prompt", e)
                Toast.makeText(context, "Fingerprint error: ${e.message}", Toast.LENGTH_SHORT).show()
                onError(e.message ?: "Sensor error")
            }
        } else {
            onError("Biometric authentication requires Android 9+")
        }
    }
}

