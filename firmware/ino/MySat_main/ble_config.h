// SHAHAR-SAT 1 — BLE configuration
//
// UUIDs, device identity, security and rate constants for the BLE layer.
// This is the single source of truth these values must match:
//   docs/BLE_PROTOCOL.md   — wire specification
//   android/.../ble/Uuids.kt — Android-side mirror of the UUIDs below
//
// UUIDs are fixed for the life of the project. Never regenerate them — a
// changed UUID silently breaks every already-paired Android install.
#pragma once

#define BLE_DEVICE_NAME "SHAHAR-SAT-1"

#define BLE_SERVICE_UUID          "7a3e0000-5b1f-4c9a-9d21-0e6f3a8b4c10"
#define BLE_CHAR_ATTITUDE_UUID    "7a3e0001-5b1f-4c9a-9d21-0e6f3a8b4c10"
#define BLE_CHAR_POWER_UUID       "7a3e0002-5b1f-4c9a-9d21-0e6f3a8b4c10"
#define BLE_CHAR_ENVIRONMENT_UUID "7a3e0003-5b1f-4c9a-9d21-0e6f3a8b4c10"
#define BLE_CHAR_SYSTEM_UUID      "7a3e0004-5b1f-4c9a-9d21-0e6f3a8b4c10"
#define BLE_CHAR_COMMAND_UUID     "7a3e0010-5b1f-4c9a-9d21-0e6f3a8b4c10"
#define BLE_CHAR_RESPONSE_UUID    "7a3e0011-5b1f-4c9a-9d21-0e6f3a8b4c10"
#define BLE_CHAR_EVENT_UUID       "7a3e0012-5b1f-4c9a-9d21-0e6f3a8b4c10"

// Static bonding passkey (6 digits), see docs/BLE_PROTOCOL.md §8.
// CHANGE THIS before the kit leaves your desk — a value committed to a
// public repository protects nobody. Changing it requires removing the
// existing Android bond first (Android Settings > Bluetooth > forget
// device) so it re-pairs with the new value.
#define BLE_STATIC_PASSKEY 123456

// Notification rates, see docs/BLE_PROTOCOL.md §6 and TELEMETRY.md §3.
#define BLE_ATTITUDE_INTERVAL_MS     100   // 10 Hz
#define BLE_POWER_INTERVAL_MS        500   // 2 Hz
#define BLE_ENVIRONMENT_INTERVAL_MS  1000  // 1 Hz
#define BLE_SYSTEM_MIN_INTERVAL_MS   5000  // keep-alive floor; also sends immediately on change

#define BLE_MAX_FRAME_LEN    244  // pessimistic ATT payload (247-byte MTU - 3), never fragment
#define BLE_MAX_CMD_LEN       200 // command payload cap, see docs/BLE_PROTOCOL.md §7.1
#define BLE_COMMAND_QUEUE_LEN   8
#define BLE_PREFERRED_MTU     247

#define BLE_PROTOCOL_VERSION 1
