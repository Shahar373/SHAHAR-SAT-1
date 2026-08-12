package com.shahar.shaharsat.ui.attitude

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shahar.shaharsat.gl.CubeSatRenderer
import com.shahar.shaharsat.gl.Quaternion
import com.shahar.shaharsat.ui.theme.MissionColors

/**
 * 3D attitude visualization. Body axes and the Euler->quaternion
 * convention are documented in docs/BLE_PROTOCOL.md §6.1 — this screen
 * is the one place that convention gets consumed, via
 * gl/Quaternion.fromEulerZYXDegrees + CubeSatRenderer's per-frame SLERP.
 */
@Composable
fun AttitudeScreen(viewModel: AttitudeViewModel, onBack: () -> Unit) {
    val attitude by viewModel.attitude.collectAsState()
    val zeroing by viewModel.zeroing.collectAsState()
    val context = LocalContext.current
    val renderer = remember { CubeSatRenderer() }

    LaunchedEffect(attitude) {
        val a = attitude
        if (a != null && a.isCalibrated && a.r != null && a.p != null && a.y != null) {
            renderer.targetRotation = Quaternion.fromEulerZYXDegrees(a.r, a.p, a.y)
        }
    }

    Scaffold(
        containerColor = MissionColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Attitude") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MissionColors.Background)
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = {
                    GLSurfaceView(context).apply {
                        setEGLContextClientVersion(2)
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }
                }
            )

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val calibrated = attitude?.isCalibrated == true
                if (!calibrated) {
                    Text(
                        "IMU not calibrated — orientation is not shown. Use CALIBRATE_IMU on the Engineering screen.",
                        style = MaterialTheme.typography.bodyMedium, color = MissionColors.AccentAmber
                    )
                } else {
                    Text(
                        "Yaw is relative and drifts over time (no magnetometer — see docs/TELEMETRY.md). Re-zero if it disagrees with reality.",
                        style = MaterialTheme.typography.bodyMedium, color = MissionColors.TextSecondary
                    )
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    AxisReadout("Roll (X)", attitude?.r)
                    AxisReadout("Pitch (Y)", attitude?.p)
                    AxisReadout("Yaw (Z)", attitude?.y)
                }
                Button(onClick = viewModel::zeroAttitude, enabled = !zeroing && calibrated) {
                    Text(if (zeroing) "Zeroing..." else "Zero Attitude")
                }
            }
        }
    }
}

@Composable
private fun AxisReadout(label: String, value: Float?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MissionColors.TextSecondary)
        Text(
            value?.let { String.format(java.util.Locale.US, "%.1f°", it) } ?: "--",
            style = MaterialTheme.typography.bodyLarge, color = MissionColors.TextPrimary
        )
    }
}
