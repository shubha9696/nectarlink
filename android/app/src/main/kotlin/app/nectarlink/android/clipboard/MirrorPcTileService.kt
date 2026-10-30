package app.nectarlink.android.clipboard

import android.content.Intent
import android.service.quicksettings.TileService
import app.nectarlink.android.ui.MainActivity

class MirrorPcTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("route", "remote/screen")
        }
        startActivityAndCollapse(intent)
    }
}
