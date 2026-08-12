package com.shahar.shaharsat.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.shahar.shaharsat.ble.ConnectionState
import com.shahar.shaharsat.ble.RawPacketLogEntry
import com.shahar.shaharsat.data.Command
import com.shahar.shaharsat.ui.controls.ConfirmCommandDialog
import com.shahar.shaharsat.ui.dashboard.MetricRow
import com.shahar.shaharsat.ui.dashboard.MissionCard
import com.shahar.shaharsat.ui.theme.MissionColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Engineering/debug screen — connection internals plus every command
 * that isn't one of the Dashboard's four primary controls. See
 * docs/TEST_PLAN.md §11 for what each field here is meant to verify.
 */
@Composable
fun EngineeringScreen(viewModel: EngineeringViewModel, onBack: () -> Unit) {
    val connectionState by viewModel.connectionState.collectAsState()
    val linkInfo by viewModel.linkInfo.collectAsState()
    val parseErrors by viewModel.parseErrorCount.collectAsState()
    val lastTelemetry by viewModel.lastTelemetryTimestampMs.collectAsState()
    val rawLog by viewModel.rawLog.collectAsState()
    val rxCount by viewModel.rxCount.collectAsState()
    val txCount by viewModel.txCount.collectAsState()
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
        topBar = {
            TopAppBar(
                title = { Text("Engineering") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MissionColors.Background)
            )
        },
        snackbarHost = {
            lastError?.let { message ->
                Snackbar(modifier = Modifier.padding(12.dp), action = { TextButton(onClick = viewModel::dismissError) { Text("Dismiss") } }) {
                    Text(message)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item { ConnectionCard(connectionState, linkInfo, parseErrors, lastTelemetry, rxCount, txCount) }
            item { CommandsCard(pending, onExecute = { cmd -> if (cmd.requiresConfirmation) confirming = cmd else viewModel.execute(cmd) }) }
            item { Text("RAW PACKET LOG", style = MaterialTheme.typography.labelSmall, color = MissionColors.TextSecondary) }
            items(rawLog.asReversed()) { entry -> RawLogRow(entry) }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ConnectionState,
    link: com.shahar.shaharsat.ble.LinkInfo,
    parseErrors: Int,
    lastTelemetryMs: Long?,
    rxCount: Int,
    txCount: Int
) {
    MissionCard("CONNECTION") {
        MetricRow("State", state::class.simpleName)
        MetricRow("Address", link.deviceAddress)
        MetricRow("RSSI", link.rssi?.toString(), " dBm")
        MetricRow("MTU", link.mtu.toString())
        MetricRow("Bonded", if (link.bonded) "yes" else if (link.bonding) "pairing..." else "no")
        MetricRow("Services discovered", if (link.servicesDiscovered) "yes" else "no")
        MetricRow("RX / TX packets", "$rxCount / $txCount")
        MetricRow("Parse errors", parseErrors.toString())
        MetricRow("Last telemetry", lastTelemetryMs?.let { formatTime(it) } ?: "never")
        link.lastError?.let { MetricRow("Last GATT error", it) }
    }
}

@Composable
private fun CommandsCard(pending: Set<Command>, onExecute: (Command) -> Unit) {
    val secondary = listOf(
        Command.ZERO_ATTITUDE, Command.CALIBRATE_IMU,
        Command.SAFE_MODE, Command.NOMINAL_MODE, Command.DESKTOP_MODE,
        Command.WIFI_ON, Command.WIFI_OFF, Command.STOP_LOGGING, Command.REBOOT
    )
    MissionCard("COMMANDS") {
        FlowRowLike(secondary, pending, onExecute)
    }
}

@Composable
private fun FlowRowLike(commands: List<Command>, pending: Set<Command>, onExecute: (Command) -> Unit) {
    // Simple 2-per-row layout — avoids pulling in the experimental FlowRow API for a v1 debug screen.
    commands.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { command ->
                OutlinedButton(
                    onClick = { onExecute(command) },
                    enabled = command !in pending,
                    modifier = Modifier.weight(1f)
                ) { Text(command.wireName, style = MaterialTheme.typography.labelSmall) }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RawLogRow(entry: RawPacketLogEntry) {
    val color = when (entry.direction) {
        RawPacketLogEntry.Direction.RX -> MissionColors.AccentBlue
        RawPacketLogEntry.Direction.TX -> MissionColors.AccentGreen
        RawPacketLogEntry.Direction.ERROR -> MissionColors.AccentRed
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "${entry.direction} ${entry.characteristic.toString().takeLast(4)} @ ${formatTime(entry.timestampMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
        Text(entry.text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MissionColors.TextSecondary)
    }
}

private fun formatTime(ms: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))
