package com.shahar.shaharsat.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.ThreeDRotation
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shahar.shaharsat.data.BatteryEstimate
import com.shahar.shaharsat.data.Command
import com.shahar.shaharsat.ui.controls.ConfirmCommandDialog
import com.shahar.shaharsat.ui.theme.MissionColors

/**
 * Main screen: SPACECRAFT / POWER / ENVIRONMENT / ATTITUDE / SOLAR cards
 * plus the four primary controls, per the original design brief. Every
 * other command (modes, WiFi, calibration, reboot) lives on the
 * Engineering screen so this one stays uncluttered.
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenAttitude: () -> Unit,
    onOpenEngineering: () -> Unit
) {
    val attitude by viewModel.attitude.collectAsState()
    val power by viewModel.power.collectAsState()
    val environment by viewModel.environment.collectAsState()
    val system by viewModel.system.collectAsState()
    val pending by viewModel.pendingCommands.collectAsState()
    val lastError by viewModel.lastError.collectAsState()

    var confirming by remember { mutableStateOf<Command?>(null) }

    confirming?.let { command ->
        ConfirmCommandDialog(
            command = command,
            onConfirm = { viewModel.execute(command); confirming = null },
            onDismiss = { confirming = null }
        )
    }

    Scaffold(
        containerColor = MissionColors.Background,
        topBar = { DashboardHeader(connected = system != null, onOpenEngineering = onOpenEngineering) },
        bottomBar = {
            ControlsBar(
                pending = pending,
                onDeploy = { confirming = Command.DEPLOY_SOLAR },
                onRetract = { confirming = Command.RETRACT_SOLAR },
                onPhoto = { viewModel.execute(Command.TAKE_PHOTO) },
                onBlink = { viewModel.execute(Command.BLINK_LED) }
            )
        },
        snackbarHost = {
            lastError?.let { message ->
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    action = { TextButton(onClick = viewModel::dismissError) { Text("Dismiss") } }
                ) { Text(message) }
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item { SpacecraftCard(system) }
            item { PowerCard(power) }
            item { EnvironmentCard(environment) }
            item { AttitudeCard(attitude, onOpenAttitude) }
            item { SolarCard(power, system) }
        }
    }
}

@Composable
private fun DashboardHeader(connected: Boolean, onOpenEngineering: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text("SHAHAR-SAT 1", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.size(8.dp)
                            .background(if (connected) MissionColors.AccentGreen else MissionColors.AccentRed, androidx.compose.foundation.shape.CircleShape)
                    )
                    Text(
                        if (connected) "CONNECTED" else "DISCONNECTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (connected) MissionColors.AccentGreen else MissionColors.AccentRed
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onOpenEngineering) {
                Icon(Icons.Filled.SettingsInputAntenna, contentDescription = "Engineering")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MissionColors.Background)
    )
}

@Composable
private fun ControlsBar(
    pending: Set<Command>,
    onDeploy: () -> Unit,
    onRetract: () -> Unit,
    onPhoto: () -> Unit,
    onBlink: () -> Unit
) {
    Surface(color = MissionColors.Surface, tonalElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ControlButton("DEPLOY", Icons.Filled.Unarchive, Command.DEPLOY_SOLAR !in pending, Modifier.weight(1f), onDeploy)
            ControlButton("RETRACT", Icons.Filled.Height, Command.RETRACT_SOLAR !in pending, Modifier.weight(1f), onRetract)
            ControlButton("PHOTO", Icons.Filled.CameraAlt, Command.TAKE_PHOTO !in pending, Modifier.weight(1f), onPhoto)
            ControlButton("BLINK", Icons.Filled.FlashOn, Command.BLINK_LED !in pending, Modifier.weight(1f), onBlink)
        }
    }
}

@Composable
private fun ControlButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SpacecraftCard(system: com.shahar.shaharsat.data.SystemFrame?) {
    MissionCard("SPACECRAFT") {
        MetricRow("Mode", system?.mode)
        MetricRow("Uptime", system?.up?.let { formatUptime(it) })
        MetricRow("Firmware", system?.fw)
        MetricRow("Call sign", system?.cs)
    }
}

@Composable
private fun PowerCard(power: com.shahar.shaharsat.data.PowerFrame?) {
    val pct = BatteryEstimate.estimatePercent(power?.bv)
    val charging = BatteryEstimate.inferCharging(power?.bv, power?.bi)
    MissionCard("POWER") {
        MetricRow("Battery", pct?.let { "$it% (est.)" })
        MetricRow("Voltage", power?.bv.fmt(2), " V")
        MetricRow("Current", power?.bi.fmt(1), " mA")
        MetricRow("Solar power", power?.sp.fmt(1), " mW")
        MetricRow("Charging", charging?.let { if (it) "yes (inferred)" else "no (inferred)" })
    }
}

@Composable
private fun EnvironmentCard(environment: com.shahar.shaharsat.data.EnvironmentFrame?) {
    MissionCard("ENVIRONMENT") {
        MetricRow("Temperature", environment?.tc.fmt(1), " °C")
        MetricRow("Pressure", environment?.hpa.fmt(1), " hPa")
        MetricRow("Humidity", environment?.rh.fmt(1), " %")
        MetricRow("IAQ", environment?.iaq.fmt(0))
    }
}

@Composable
private fun AttitudeCard(attitude: com.shahar.shaharsat.data.AttitudeFrame?, onOpenAttitude: () -> Unit) {
    MissionCard("ATTITUDE") {
        MetricRow("Roll", attitude?.r.fmt(1), "°")
        MetricRow("Pitch", attitude?.p.fmt(1), "°")
        MetricRow("Yaw", attitude?.y.fmt(1), "°", valueColor = MissionColors.AccentAmber)
        if (attitude != null && !attitude.isCalibrated) {
            Text("Uncalibrated — see Engineering to calibrate", style = MaterialTheme.typography.labelSmall, color = MissionColors.AccentAmber)
        }
        TextButton(onClick = onOpenAttitude, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Filled.ThreeDRotation, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("View 3D model")
        }
    }
}

@Composable
private fun SolarCard(power: com.shahar.shaharsat.data.PowerFrame?, system: com.shahar.shaharsat.data.SystemFrame?) {
    MissionCard("SOLAR") {
        MetricRow("Panels", system?.let { if (it.panelsDeployed) "Deployed (commanded)" else "Retracted (commanded)" })
        MetricRow("Solar voltage", power?.sv.fmt(2), " V")
        MetricRow("Left current", power?.sil.fmt(1), " mA")
        MetricRow("Right current", power?.sir.fmt(1), " mA")
        Text("Sun sensors (raw)", style = MaterialTheme.typography.labelSmall, color = MissionColors.TextSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("L ${power?.ph1.fmt(0) ?: "--"}", style = MaterialTheme.typography.bodyMedium)
            Text("Bk ${power?.ph2.fmt(0) ?: "--"}", style = MaterialTheme.typography.bodyMedium)
            Text("R ${power?.ph3.fmt(0) ?: "--"}", style = MaterialTheme.typography.bodyMedium)
            Text("Fr ${power?.ph4.fmt(0) ?: "--"}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
}
