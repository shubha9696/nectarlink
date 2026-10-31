// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.android.biometric.BiometricUnlock

class BiometricUnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        val core = (application as NectarlinkApplication).core
        val pc = core.state.value.devices.firstOrNull { it.online }
        if (pc != null) {
            BiometricUnlock.unlock(
                context = this,
                pcId = pc.id,
                core = core,
                onSuccess = { finish() },
                onError = { err ->
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                    finish()
                }
            )
        } else {
            finish()
        }
    }
}
