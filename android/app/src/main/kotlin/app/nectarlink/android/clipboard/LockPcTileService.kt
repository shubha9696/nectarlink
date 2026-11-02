// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.clipboard

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.nectarlink.android.NectarlinkApplication

/** The "Lock PC" Quick Settings tile. */
class LockPcTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val app = application as? NectarlinkApplication ?: return
        val online = app.core.state.value.devices.any { it.online }
        qsTile?.state = if (online) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val app = application as? NectarlinkApplication ?: return
        val core = app.core
        val pc = core.state.value.devices.firstOrNull { it.online } ?: core.state.value.devices.firstOrNull() ?: return
        core.pcPower(pc.id, sleep = false)
    }
}

