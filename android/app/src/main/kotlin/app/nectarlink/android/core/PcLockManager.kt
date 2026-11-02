// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PcLockManager {
    private val _pcLocked = MutableStateFlow(false)
    val pcLocked: StateFlow<Boolean> = _pcLocked.asStateFlow()

    private val _pcBluetooth = MutableStateFlow<Boolean?>(null)
    val pcBluetooth: StateFlow<Boolean?> = _pcBluetooth.asStateFlow()

    fun setPcLocked(locked: Boolean) {
        _pcLocked.value = locked
    }

    fun setPcBluetooth(on: Boolean?) {
        _pcBluetooth.value = on
    }
}
