package com.shahar.shaharsat.data

import kotlinx.serialization.Serializable

/**
 * Wire frames from firmware/ino/MySat_main/ble_telemetry.h, one class per
 * characteristic — see docs/BLE_PROTOCOL.md §6 for the authoritative
 * field list, units and omission rules.
 *
 * Every field except [v] and [t] is nullable: the firmware omits a field
 * entirely rather than sending a fake 0 when a sensor is absent or
 * uncalibrated (docs/TELEMETRY.md). Parsing code must render a missing
 * field as "--", never as 0 — see TelemetryRepository / dashboard cards.
 */
@Serializable
data class AttitudeFrame(
    val v: Int = 0,
    val t: Long = 0,
    val r: Float? = null,
    val p: Float? = null,
    val y: Float? = null,
    val cal: Int = 0
) {
    val isCalibrated: Boolean get() = cal == 1
}

@Serializable
data class PowerFrame(
    val v: Int = 0,
    val t: Long = 0,
    val bv: Float? = null,
    val bi: Float? = null,
    val sv: Float? = null,
    val sil: Float? = null,
    val sir: Float? = null,
    val sp: Float? = null,
    // Sun sensors (ADS1015, raw ADC counts, unscaled — see
    // docs/TELEMETRY.md §2.4). Folded into the Power frame rather than a
    // dedicated characteristic: they're EPS/solar-adjacent, and adding
    // them here comfortably fits the frame's MTU budget.
    val ph1: Float? = null, // left
    val ph2: Float? = null, // back
    val ph3: Float? = null, // right
    val ph4: Float? = null  // front
)

@Serializable
data class EnvironmentFrame(
    val v: Int = 0,
    val t: Long = 0,
    val tc: Float? = null,
    val rh: Float? = null,
    val hpa: Float? = null,
    val gas: Float? = null,
    val iaq: Float? = null,
    val iaqa: Int? = null
)

@Serializable
data class HardwarePresence(
    val bme: Int = 0,
    val mpu: Int = 0,
    val ads: Int = 0,
    val ina: Int = 0,
    val rtc: Int = 0,
    val cam: Int = 0
)

@Serializable
data class SystemFrame(
    val v: Int = 0,
    val t: Long = 0,
    val cs: String? = null,
    val fw: String? = null,
    val up: Long? = null,
    val mode: String? = null,
    val mot: Int? = null,
    val led: Int? = null,
    val log: Int? = null,
    val heap: Long? = null,
    val rtc: String? = null,
    val hw: HardwarePresence? = null,
    val fc: Long? = null
) {
    val panelsDeployed: Boolean get() = mot == 1
    val ledOn: Boolean get() = led == 1
}

@Serializable
data class CommandResponse(
    val seq: Int,
    val cmd: String,
    val ok: Boolean,
    val err: String? = null,
    val msg: String? = null
)

@Serializable
data class SpacecraftEvent(
    val t: Long,
    val ev: String,
    val detail: String? = null
)
