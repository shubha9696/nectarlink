// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import app.nectarlink.android.NectarlinkApplication

class RestartConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as NectarlinkApplication).core
        val pcId = intent?.getStringExtra("pc_id")
        val pc = if (!pcId.isNullOrEmpty()) {
            core.state.value.devices.firstOrNull { it.id == pcId }
        } else {
            core.state.value.devices.firstOrNull { it.online }
        }
        val targetId = pc?.id ?: pcId
        val pcName = pc?.name ?: "PC"

        AlertDialog.Builder(this)
            .setTitle("Restart $pcName?")
            .setMessage("Are you sure you want to restart $pcName? Any unsaved work on your computer will be lost.")
            .setPositiveButton("Restart") { _, _ ->
                if (targetId != null) {
                    core.pcRestart(targetId)
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
    }
}
