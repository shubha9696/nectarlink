// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.biometric

import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
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
                            Toast.makeText(context, "Unlocking PC...", Toast.LENGTH_SHORT).show()
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            onError(errString?.toString() ?: "Authentication failed")
                        }
                    },
                )
            } catch (e: Exception) {
                // Fallback: send unlock signal directly
                core.pcUnlock(pcId)
                onSuccess()
            }
        } else {
            core.pcUnlock(pcId)
            onSuccess()
        }
    }
}

