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

        val prefs = context.getSharedPreferences("pc_unlock_prefs", Context.MODE_PRIVATE)
        val savedPin = prefs.getString("pin_$pcId", null)?.trim()

        if (savedPin.isNullOrEmpty()) {
            val input = android.widget.EditText(context).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "Enter Windows PIN"
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Enter Windows PIN")
                .setMessage("Save your Windows PIN on phone so your fingerprint can unlock directly to desktop.")
                .setView(input)
                .setPositiveButton("Save & Unlock") { _, _ ->
                    val pinText = input.text.toString().trim()
                    if (pinText.isNotEmpty()) {
                        prefs.edit().putString("pin_$pcId", pinText).apply()
                        authenticateAndUnlock(context, pcId, core, pinText, onSuccess, onError)
                    } else {
                        authenticateAndUnlock(context, pcId, core, null, onSuccess, onError)
                    }
                }
                .setNegativeButton("Cancel") { _, _ ->
                    onError("PIN required")
                }
                .setOnCancelListener {
                    onError("Cancelled")
                }
                .show()
        } else {
            authenticateAndUnlock(context, pcId, core, savedPin, onSuccess, onError)
        }
    }

    fun promptPinChange(context: Context, pcId: String) {
        val prefs = context.getSharedPreferences("pc_unlock_prefs", Context.MODE_PRIVATE)
        val input = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Windows PIN"
            setText(prefs.getString("pin_$pcId", ""))
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("Set Windows PIN")
            .setMessage("Enter the PIN used by your Windows lock screen to unlock directly to the desktop.")
            .setView(input)
            .setPositiveButton("Save PIN") { _, _ ->
                val pinText = input.text.toString().trim()
                prefs.edit().putString("pin_$pcId", pinText).apply()
                android.widget.Toast.makeText(context, "Windows PIN saved", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    }

    private fun authenticateAndUnlock(
        context: Activity,
        pcId: String,
        core: Core,
        pin: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
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
                            core.pcUnlock(pcId, pin)
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
            core.pcUnlock(pcId, pin)
            app.nectarlink.android.core.PcLockManager.setPcLocked(false)
            onSuccess()
        }
    }
}

