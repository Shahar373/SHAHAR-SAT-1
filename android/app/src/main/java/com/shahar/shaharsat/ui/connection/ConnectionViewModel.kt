package com.shahar.shaharsat.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahar.shaharsat.ble.BleClient
import com.shahar.shaharsat.ble.ConnectionState
import com.shahar.shaharsat.ble.LinkInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin wrapper over [BleClient]'s connection lifecycle for the Connection
 * screen. Permission *requests* happen in the Composable (only an
 * Activity can launch that dialog) — this only reacts once permissions
 * are known to be granted. See docs/TEST_PLAN.md §7.
 */
class ConnectionViewModel(private val bleClient: BleClient) : ViewModel() {
    val connectionState: StateFlow<ConnectionState> = bleClient.connectionState
    val linkInfo: StateFlow<LinkInfo> = bleClient.linkInfo

    /** Called once permissions are confirmed granted (fresh grant, or already held on launch). */
    fun onPermissionsReady() {
        bleClient.start()
    }

    /** Manual retry button — safe to call repeatedly, never queues a background loop itself. */
    fun retry() {
        bleClient.start()
    }

    override fun onCleared() {
        super.onCleared()
        // Connection should outlive this screen (dashboard depends on it
        // staying connected) — no bleClient.disconnect() here. Only
        // SatApplication's process teardown calls bleClient.shutdown().
    }
}
