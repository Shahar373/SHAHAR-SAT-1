package com.shahar.shaharsat.ui.connection

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.shahar.shaharsat.ble.ConnectionState
import com.shahar.shaharsat.ui.theme.MissionColors

/**
 * The whole point of this screen is the UX promised in
 * docs/ARCHITECTURE.md: open the app, see "Searching for spacecraft...",
 * then "SHAHAR-SAT 1 / CONNECTED" — no IP addresses, no terminal, no
 * manual pairing flow shown to the user beyond the one-time Android
 * system passkey dialog. See docs/TEST_PLAN.md §7.
 */
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onConnected: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) viewModel.onPermissionsReady()
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.retry() }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            viewModel.onPermissionsReady()
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) onConnected()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MissionColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            PulsingSatelliteIcon(active = connectionState is ConnectionState.Scanning || connectionState is ConnectionState.Connecting)

            Text("SHAHAR-SAT 1", style = MaterialTheme.typography.titleLarge, color = MissionColors.TextPrimary)

            StatusLine(connectionState)

            when (connectionState) {
                is ConnectionState.PermissionsRequired -> Button(onClick = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                }) { Text("Grant Bluetooth Permissions") }

                is ConnectionState.BluetoothOff -> Button(onClick = {
                    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }) { Text("Enable Bluetooth") }

                is ConnectionState.Idle, is ConnectionState.Disconnected, is ConnectionState.Error ->
                    OutlinedButton(onClick = { viewModel.retry() }) { Text("Search Again") }

                else -> Unit
            }
        }
    }
}

@Composable
private fun StatusLine(state: ConnectionState) {
    val (text, color) = when (state) {
        is ConnectionState.Idle -> "Ready to search" to MissionColors.TextSecondary
        is ConnectionState.Scanning -> "Searching for spacecraft..." to MissionColors.AccentBlue
        is ConnectionState.Connecting -> "Connecting..." to MissionColors.AccentBlue
        is ConnectionState.Bonding -> "Pairing — enter the passkey on your phone" to MissionColors.AccentAmber
        is ConnectionState.Connected -> "CONNECTED" to MissionColors.AccentGreen
        is ConnectionState.Disconnected -> "DISCONNECTED" to MissionColors.AccentRed
        is ConnectionState.PermissionsRequired -> "Bluetooth permissions required" to MissionColors.AccentAmber
        is ConnectionState.BluetoothOff -> "Bluetooth is off" to MissionColors.AccentAmber
        is ConnectionState.Error -> state.message to MissionColors.AccentRed
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color, fontFamily = FontFamily.Monospace)
}

@Composable
private fun PulsingSatelliteIcon(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "scan-pulse")
    val rotation by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation"
    )
    Icon(
        imageVector = Icons.Filled.SatelliteAlt,
        contentDescription = null,
        tint = MissionColors.AccentGreen,
        modifier = Modifier.size(72.dp).rotate(if (active) rotation else 0f)
    )
}
