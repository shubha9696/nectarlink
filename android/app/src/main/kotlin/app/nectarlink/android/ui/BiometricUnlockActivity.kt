// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui

import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.android.biometric.BiometricUnlock

class BiometricUnlockActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as NectarlinkApplication).core
        val pc = core.state.value.devices.firstOrNull { it.online }
        val pcName = pc?.name ?: "PC"

        BiometricUnlock.authenticate(
            activity = this,
            title = "Unlock $pcName",
            subtitle = "Touch fingerprint sensor to unlock",
            onSuccess = {
                if (pc != null) {
                    core.pcUnlock(pc.id)
                    Toast.makeText(this, "Unlocking $pcName...", Toast.LENGTH_SHORT).show()
                }
                finish()
            },
            onError = { err ->
                Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                finish()
            }
        )
    }
}
