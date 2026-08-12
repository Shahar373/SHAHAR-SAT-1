package com.shahar.shaharsat.ble

/**
 * Connection lifecycle as the UI needs to render it. See
 * docs/BLE_PROTOCOL.md §9 for the error/edge-case list this is designed
 * to cover, and docs/TEST_PLAN.md §7 for how each state is verified.
 *
 * There is deliberately no `Bonding` state here. An earlier draft had one,
 * but it never actually got assigned anywhere — [ConnectionState.Connected]
 * fires once GATT setup finishes, independent of bonding, because
 * PING/GET_STATUS work before pairing (docs/BLE_PROTOCOL.md §8). Bonding
 * progress is real, but it's not a phase this connection passes through on
 * its way to Connected — it happens in the background afterward, so it's
 * tracked in [LinkInfo.bonding] instead, where the UI can show it as a
 * status alongside an already-usable connection rather than a blocking step.
 */
sealed interface ConnectionState {
    data object BluetoothOff : ConnectionState
    data object PermissionsRequired : ConnectionState
    data object Idle : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val deviceAddress: String) : ConnectionState
    data class Connected(val deviceAddress: String, val deviceName: String?) : ConnectionState
    data class Disconnected(val reason: String? = null) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/** GATT/engineering-facing detail, independent of the coarse [ConnectionState] above. */
data class LinkInfo(
    val deviceAddress: String? = null,
    val rssi: Int? = null,
    val mtu: Int = 23,
    val servicesDiscovered: Boolean = false,
    val bonding: Boolean = false,
    val bonded: Boolean = false,
    val lastError: String? = null
)
