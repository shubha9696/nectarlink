// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import app.nectarlink.android.NectarlinkApplication

class RestartConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val app = application as? NectarlinkApplication
            val core = app?.core
            val pc = core?.state?.value?.devices?.firstOrNull { it.online }
                ?: core?.state?.value?.devices?.firstOrNull()
            val pcName = pc?.name ?: "PC"

            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Restart $pcName?")
                .setMessage("Are you sure you want to restart your PC? Any unsaved work will be lost.")
                .setPositiveButton("Restart") { _, _ ->
                    if (pc != null) {
                        core?.pcRestart(pc.id)
                        Toast.makeText(this, "Restarting $pcName...", Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
                .setNegativeButton("Cancel") { _, _ ->
                    finish()
                }
                .setOnDismissListener {
                    finish()
                }
                .show()
        } catch (e: Throwable) {
            Log.e("RestartConfirm", "Failed to display restart dialog", e)
            finish()
        }
    }
}
