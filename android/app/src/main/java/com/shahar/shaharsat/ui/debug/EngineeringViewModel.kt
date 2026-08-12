package com.shahar.shaharsat.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahar.shaharsat.ble.BleClient
import com.shahar.shaharsat.ble.ConnectionState
import com.shahar.shaharsat.ble.LinkInfo
import com.shahar.shaharsat.ble.RawPacketLogEntry
import com.shahar.shaharsat.data.Command
import com.shahar.shaharsat.data.TelemetryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val RAW_LOG_MAX = 100

/**
 * Backs the Engineering screen: connection/GATT diagnostics (address,
 * RSSI, MTU, service state, raw packet log, parse-error and packet
 * counters) plus the secondary command set that's deliberately kept off
 * the main Dashboard. See docs/TEST_PLAN.md §11.
 */
class EngineeringViewModel(
    bleClient: BleClient,
    private val telemetryRepository: TelemetryRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = bleClient.connectionState
    val linkInfo: StateFlow<LinkInfo> = bleClient.linkInfo
    val parseErrorCount: StateFlow<Int> = telemetryRepository.parseErrorCount
    val lastTelemetryTimestampMs: StateFlow<Long?> = telemetryRepository.lastTelemetryTimestampMs

    private val _rawLog = MutableStateFlow<List<RawPacketLogEntry>>(emptyList())
    val rawLog: StateFlow<List<RawPacketLogEntry>> = _rawLog.asStateFlow()

    private val _rxCount = MutableStateFlow(0)
    val rxCount: StateFlow<Int> = _rxCount.asStateFlow()

    private val _txCount = MutableStateFlow(0)
    val txCount: StateFlow<Int> = _txCount.asStateFlow()

    private val _pendingCommands = MutableStateFlow<Set<Command>>(emptySet())
    val pendingCommands: StateFlow<Set<Command>> = _pendingCommands.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val commandSender = com.shahar.shaharsat.ble.CommandSender(bleClient, telemetryRepository.commandResponses, viewModelScope)

    init {
        viewModelScope.launch {
            bleClient.rawLog.collect { entry ->
                when (entry.direction) {
                    RawPacketLogEntry.Direction.RX -> _rxCount.update { it + 1 }
                    RawPacketLogEntry.Direction.TX -> _txCount.update { it + 1 }
                    RawPacketLogEntry.Direction.ERROR -> Unit
                }
                _rawLog.update { (it + entry).takeLast(RAW_LOG_MAX) }
            }
        }
    }

    fun execute(command: Command) {
        if (command in _pendingCommands.value) return
        _pendingCommands.update { it + command }
        viewModelScope.launch {
            val outcome = commandSender.send(command)
            _lastError.value = when (outcome) {
                is com.shahar.shaharsat.ble.CommandOutcome.Success -> null
                is com.shahar.shaharsat.ble.CommandOutcome.Failed -> outcome.response.msg ?: outcome.response.err
                com.shahar.shaharsat.ble.CommandOutcome.NotConnected -> "Not connected"
                com.shahar.shaharsat.ble.CommandOutcome.TimedOut -> "${command.wireName}: no response"
            }
            _pendingCommands.update { it - command }
        }
    }

    fun dismissError() {
        _lastError.value = null
    }
}
