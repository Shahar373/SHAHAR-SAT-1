package com.shahar.shaharsat.ui.theme

import androidx.compose.ui.graphics.Color

// SHAHAR-SAT 1 — "mission control" palette. Dark-only per the design
// brief: professional operations-dashboard feel, not cyberpunk/terminal.
object MissionColors {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF121821)
    val SurfaceVariant = Color(0xFF1B2330)
    val Outline = Color(0xFF2A3441)

    val TextPrimary = Color(0xFFE8EDF3)
    val TextSecondary = Color(0xFF8FA0B3)
    val TextDisabled = Color(0xFF4E5A69)

    val AccentGreen = Color(0xFF00E5A0)   // nominal / connected / success
    val AccentBlue = Color(0xFF3DA5F5)    // informational / solar
    val AccentAmber = Color(0xFFF5A623)   // caution (e.g. yaw drift, low battery)
    val AccentRed = Color(0xFFF5473D)     // fault / disconnected / destructive

    val ChartGrid = Color(0xFF232D3B)
}
