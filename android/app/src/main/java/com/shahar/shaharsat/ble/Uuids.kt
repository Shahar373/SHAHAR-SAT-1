package com.shahar.shaharsat.ble

import java.util.UUID

/**
 * Mirrors firmware/ino/MySat_main/ble_config.h exactly — see
 * docs/BLE_PROTOCOL.md §2. These UUIDs are fixed for the life of the
 * project; if they ever need to change, both sides change together.
 */
object Uuids {
    const val DEVICE_NAME = "SHAHAR-SAT-1"

    val SERVICE: UUID = UUID.fromString("7a3e0000-5b1f-4c9a-9d21-0e6f3a8b4c10")

    val CHAR_ATTITUDE: UUID = UUID.fromString("7a3e0001-5b1f-4c9a-9d21-0e6f3a8b4c10")
    val CHAR_POWER: UUID = UUID.fromString("7a3e0002-5b1f-4c9a-9d21-0e6f3a8b4c10")
    val CHAR_ENVIRONMENT: UUID = UUID.fromString("7a3e0003-5b1f-4c9a-9d21-0e6f3a8b4c10")
    val CHAR_SYSTEM: UUID = UUID.fromString("7a3e0004-5b1f-4c9a-9d21-0e6f3a8b4c10")
    val CHAR_COMMAND: UUID = UUID.fromString("7a3e0010-5b1f-4c9a-9d21-0e6f3a8b4c10")
    val CHAR_RESPONSE: UUID = UUID.fromString("7a3e0011-5b1f-4c9a-9d21-0e6f3a8b4c10")
    val CHAR_EVENT: UUID = UUID.fromString("7a3e0012-5b1f-4c9a-9d21-0e6f3a8b4c10")

    // Standard Client Characteristic Configuration Descriptor (0x2902),
    // used to enable notifications on each notify characteristic.
    val CLIENT_CHARACTERISTIC_CONFIG: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
