package app.nectarlink.android.clipboard

import android.content.Intent
import android.service.quicksettings.TileService
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LockPcTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivityAndCollapse(intent)
    }
}
