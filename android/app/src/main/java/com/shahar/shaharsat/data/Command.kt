package com.shahar.shaharsat.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The BLE-visible command set — mirrors the COMMAND_TABLE in
 * firmware/ino/MySat_main/command_bus.h and docs/COMMANDS.md. [requiresConfirmation]
 * drives the confirmation dialog in ui/controls; [requiresAuth] mirrors
 * the firmware's own auth gate purely for local UI hints (the firmware
 * enforces it regardless of what the app assumes).
 */
enum class Command(
    val wireName: String,
    val requiresAuth: Boolean = true,
    val requiresConfirmation: Boolean = false
) {
    PING("PING", requiresAuth = false),
    GET_STATUS("GET_STATUS", requiresAuth = false),
    DEPLOY_SOLAR("DEPLOY_SOLAR", requiresConfirmation = true),
    RETRACT_SOLAR("RETRACT_SOLAR", requiresConfirmation = true),
    BLINK_LED("BLINK_LED"),
    LED_ON("LED_ON"),
    LED_OFF("LED_OFF"),
    TAKE_PHOTO("TAKE_PHOTO"),
    ZERO_ATTITUDE("ZERO_ATTITUDE"),
    CALIBRATE_IMU("CALIBRATE_IMU", requiresConfirmation = true),
    SAFE_MODE("SAFE_MODE", requiresConfirmation = true),
    NOMINAL_MODE("NOMINAL_MODE"),
    DESKTOP_MODE("DESKTOP_MODE"),
    WIFI_ON("WIFI_ON"),
    WIFI_OFF("WIFI_OFF"),
    STOP_LOGGING("STOP_LOGGING"),
    REBOOT("REBOOT", requiresConfirmation = true);
}

@Serializable
private data class CommandRequestWire(val cmd: String, val seq: Int)

/**
 * Encodes a command + sequence number into the JSON payload the firmware
 * expects on the Command characteristic (docs/BLE_PROTOCOL.md §7.1).
 */
fun encodeCommand(command: Command, seq: Int): ByteArray =
    Json.encodeToString(CommandRequestWire(command.wireName, seq)).encodeToByteArray()
