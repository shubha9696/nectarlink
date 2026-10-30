// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.clipboard

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.android.ui.MainActivity

/** The "Screen Mirror" Quick Settings tile. */
class MirrorPcTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val app = application as? NectarlinkApplication ?: return
        val online = app.core.devices.value.any { it.online }
        qsTile?.state = if (online) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val app = application as? NectarlinkApplication ?: return
        val core = app.core
        val pc = core.devices.value.firstOrNull { it.online } ?: core.devices.value.firstOrNull()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("remote_pc", pc?.id)
            putExtra("remote_mode", "screen")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    101,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

