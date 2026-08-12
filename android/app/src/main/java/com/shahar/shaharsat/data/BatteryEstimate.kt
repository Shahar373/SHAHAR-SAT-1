package com.shahar.shaharsat.data

/**
 * Battery percentage and charging state are NOT firmware telemetry — the
 * INA3221 reports voltage/current only, with no chemistry constant
 * anywhere in the firmware. Both values here are estimated/inferred
 * client-side and must be labelled as such in the UI. See
 * docs/TELEMETRY.md §5 and docs/HARDWARE_NOTES.md §3 (chemistry needs
 * hardware verification before this curve should be trusted).
 *
 * The curve below assumes a single-cell Li-ion/LiPo (3.3V empty, 4.2V
 * full), consistent with the one hint in the original firmware's web UI
 * — a JS charging-threshold check at `bV > 4.0` (server.h) — but it is a
 * placeholder, not a verified spec.
 */
object BatteryEstimate {
    private const val EMPTY_V = 3.3f
    private const val FULL_V = 4.2f
    private const val CHARGING_CURRENT_THRESHOLD_MA = -5f // matches server.h's JS heuristic

    /** Returns 0-100, or null if voltage is unavailable. */
    fun estimatePercent(batteryVoltage: Float?): Int? {
        val v = batteryVoltage ?: return null
        val fraction = ((v - EMPTY_V) / (FULL_V - EMPTY_V)).coerceIn(0f, 1f)
        return (fraction * 100).toInt()
    }

    /** Negative current = flowing into the battery, matching the firmware's INA3221 sign convention. */
    fun inferCharging(batteryVoltage: Float?, batteryCurrentMa: Float?): Boolean? {
        if (batteryVoltage == null || batteryCurrentMa == null) return null
        return batteryVoltage > 4.0f && batteryCurrentMa < CHARGING_CURRENT_THRESHOLD_MA
    }
}
