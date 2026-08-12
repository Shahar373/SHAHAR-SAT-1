package com.shahar.shaharsat.ble

/**
 * Connection lifecycle as the UI needs to render it. See
 * docs/BLE_PROTOCOL.md §9 for the error/edge-case list this is designed
 * to cover, and docs/TEST_PLAN.md §7 for how each state is verified.
 */
sealed interface ConnectionState {
    data object BluetoothOff : ConnectionState
    data object PermissionsRequired : ConnectionState
    data object Idle : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val deviceAddress: String) : ConnectionState
    data class Bonding(val deviceAddress: String) : ConnectionState
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
    val bonded: Boolean = false,
    val lastError: String? = null
)
