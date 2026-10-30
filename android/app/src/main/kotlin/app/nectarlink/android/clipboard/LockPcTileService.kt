package app.nectarlink.android.clipboard

import android.service.quicksettings.TileService
import app.nectarlink.android.NectarApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LockPcTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onClick() {
        super.onClick()
        scope.launch {
            try {
                val app = application as? NectarApplication
                // Trigger Lock WorkStation via the node core connection
                app?.node?.lockActivePc()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
