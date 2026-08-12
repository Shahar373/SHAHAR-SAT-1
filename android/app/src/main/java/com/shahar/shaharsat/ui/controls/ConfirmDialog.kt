package com.shahar.shaharsat.ui.controls

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.shahar.shaharsat.data.Command

/**
 * Names the action explicitly rather than a generic "Are you sure?" —
 * see docs/COMMANDS.md §3. [onConfirm] must be the only place that calls
 * into the ViewModel's execute() for this command; the dialog itself
 * holds no side-effecting state.
 */
@Composable
fun ConfirmCommandDialog(
    command: Command,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val (title, body) = confirmationCopy(command)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun confirmationCopy(command: Command): Pair<String, String> = when (command) {
    Command.DEPLOY_SOLAR -> "Deploy solar panels?" to "The servo will move. This can't be interrupted mid-motion."
    Command.RETRACT_SOLAR -> "Retract solar panels?" to "The servo will move. This can't be interrupted mid-motion."
    Command.CALIBRATE_IMU -> "Calibrate the IMU?" to "Takes about 7 seconds. Keep the spacecraft still and level — telemetry will pause during calibration."
    Command.SAFE_MODE -> "Enter safe mode?" to "Stops logging and retracts the solar panels."
    Command.REBOOT -> "Reboot the spacecraft?" to "The BLE connection will drop and reconnect automatically once it's back up."
    else -> "${command.wireName}?" to "Send this command to the spacecraft?"
}
