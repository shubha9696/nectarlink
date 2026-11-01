// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.android.biometric.BiometricUnlock

class BiometricUnlockActivity : Activity() {
    private var authStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Allow notification shade collapse animation to finish completely before prompting
        window.decorView.postDelayed({
            if (!authStarted && !isFinishing && !isDestroyed) {
                authStarted = true
                startUnlock()
            }
        }, 450)
    }

    private fun startUnlock() {
        val core = (application as NectarlinkApplication).core
        val pc = core.state.value.devices.firstOrNull { it.online }
        if (pc != null) {
            BiometricUnlock.unlock(
                context = this,
                pcId = pc.id,
                core = core,
                onSuccess = { finish() },
                onError = { err ->
                    // Do not show cancellation messages as error toasts
                    if (!err.contains("cancel", ignoreCase = true)) {
                        Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            )
        } else {
            finish()
        }
    }
}
