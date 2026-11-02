// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.clipboard

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.android.elevated.Elevated
import app.nectarlink.android.mirror.MirrorConsentActivity
import app.nectarlink.android.mirror.MirrorRequest
import app.nectarlink.android.mirror.MirrorService

/** The "Screen Mirror / Cast" Quick Settings tile. */
class MirrorPcTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val app = application as? NectarlinkApplication ?: return
        val online = app.core.state.value.devices.any { it.online }
        val session = MirrorService.session.value
        if (session.active && !session.soundOnly) {
            qsTile?.state = Tile.STATE_ACTIVE
            qsTile?.label = "Stop Cast"
        } else {
            qsTile?.state = if (online) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            qsTile?.label = "Cast to PC"
        }
        qsTile?.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val app = application as? NectarlinkApplication ?: return
        val core = app.core
        val session = MirrorService.session.value
        if (session.active) {
            MirrorService.stopCurrent()
            onStartListening()
            return
        }
        val pc = core.state.value.devices.firstOrNull { it.online } ?: core.state.value.devices.firstOrNull() ?: return
        val req = MirrorRequest(
            pcId = pc.id,
            maxSize = 1920,
            fps = 60,
            bitrate = 8_000_000,
            audio = true,
            stayAwake = false,
            screenOff = false,
            soundOnly = false,
        )
        if (Elevated.running && MirrorService.startElevated(this, req)) {
            onStartListening()
            return
        }
        val intent = req.toIntent(Intent(this, MirrorConsentActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
