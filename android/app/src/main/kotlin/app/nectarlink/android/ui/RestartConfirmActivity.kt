// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.nectarlink.android.NectarlinkApplication

class RestartConfirmActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as NectarlinkApplication).core
        val pc = core.state.value.devices.firstOrNull { it.online }
        val pcName = pc?.name ?: "PC"

        AlertDialog.Builder(this)
            .setTitle("Restart $pcName?")
            .setMessage("Are you sure you want to restart your PC? Any unsaved work will be lost.")
            .setPositiveButton("Restart") { _, _ ->
                if (pc != null) {
                    core.pcRestart(pc.id)
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
