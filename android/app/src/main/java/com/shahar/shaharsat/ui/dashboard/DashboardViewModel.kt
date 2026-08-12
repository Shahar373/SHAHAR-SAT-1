package com.shahar.shaharsat.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahar.shaharsat.ble.CommandOutcome
import com.shahar.shaharsat.ble.CommandSender
import com.shahar.shaharsat.data.AttitudeFrame
import com.shahar.shaharsat.data.Command
import com.shahar.shaharsat.data.EnvironmentFrame
import com.shahar.shaharsat.data.PowerFrame
import com.shahar.shaharsat.data.SystemFrame
import com.shahar.shaharsat.data.TelemetryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Dashboard screen: telemetry passthrough from
 * [TelemetryRepository] plus command execution for the four primary
 * controls (DEPLOY/RETRACT/TAKE_PHOTO/BLINK_LED).
 *
 * Commands are only ever sent from [execute], called directly from a
 * button's onClick (a genuine one-shot user event) — never from a
 * LaunchedEffect keyed on recomposable state. [pendingCommands] disables
 * a command's button for the round trip, which also makes a rapid
 * double-tap a no-op rather than two sends. See docs/COMMANDS.md §3.
 */
class DashboardViewModel(
    private val telemetryRepository: TelemetryRepository,
    private val commandSender: CommandSender
) : ViewModel() {

    val attitude: StateFlow<AttitudeFrame?> = telemetryRepository.attitude
    val power: StateFlow<PowerFrame?> = telemetryRepository.power
    val environment: StateFlow<EnvironmentFrame?> = telemetryRepository.environment
    val system: StateFlow<SystemFrame?> = telemetryRepository.system

    private val _pendingCommands = MutableStateFlow<Set<Command>>(emptySet())
    val pendingCommands: StateFlow<Set<Command>> = _pendingCommands.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun execute(command: Command) {
        if (command in _pendingCommands.value) return // already in flight — the double-tap guard
        _pendingCommands.update { it + command }
        viewModelScope.launch {
            when (val outcome = commandSender.send(command)) {
                is CommandOutcome.Success -> _lastError.value = null
                is CommandOutcome.Failed -> _lastError.value = outcome.response.msg ?: outcome.response.err
                CommandOutcome.NotConnected -> _lastError.value = "Not connected"
                CommandOutcome.TimedOut -> _lastError.value = "${command.wireName}: no response"
            }
            _pendingCommands.update { it - command }
        }
    }

    fun dismissError() {
        _lastError.value = null
    }
}
