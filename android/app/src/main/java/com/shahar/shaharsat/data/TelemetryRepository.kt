package com.shahar.shaharsat.data

import com.shahar.shaharsat.ble.BleClient
import com.shahar.shaharsat.ble.Uuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Decodes [BleClient]'s raw notification bytes into the typed frames from
 * TelemetryFrames.kt and exposes them as StateFlows for the UI layer.
 * This is the only place JSON parsing happens — see docs/BLE_PROTOCOL.md
 * §9 for the "app" error-handling table this implements: a malformed or
 * truncated frame is dropped (with [parseErrorCount] incremented for the
 * Engineering screen) and the last good value is kept, never crashing.
 */
class TelemetryRepository(private val bleClient: BleClient, scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _attitude = MutableStateFlow<AttitudeFrame?>(null)
    val attitude: StateFlow<AttitudeFrame?> = _attitude.asStateFlow()

    private val _power = MutableStateFlow<PowerFrame?>(null)
    val power: StateFlow<PowerFrame?> = _power.asStateFlow()

    private val _environment = MutableStateFlow<EnvironmentFrame?>(null)
    val environment: StateFlow<EnvironmentFrame?> = _environment.asStateFlow()

    private val _system = MutableStateFlow<SystemFrame?>(null)
    val system: StateFlow<SystemFrame?> = _system.asStateFlow()

    private val _commandResponses = MutableSharedFlow<CommandResponse>(extraBufferCapacity = 16)
    val commandResponses: SharedFlow<CommandResponse> = _commandResponses.asSharedFlow()

    private val _events = MutableSharedFlow<SpacecraftEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SpacecraftEvent> = _events.asSharedFlow()

    private val _parseErrorCount = MutableStateFlow(0)
    val parseErrorCount: StateFlow<Int> = _parseErrorCount.asStateFlow()

    private val _lastTelemetryTimestampMs = MutableStateFlow<Long?>(null)
    val lastTelemetryTimestampMs: StateFlow<Long?> = _lastTelemetryTimestampMs.asStateFlow()

    init {
        scope.launch {
            bleClient.notifications.collect { notification ->
                _lastTelemetryTimestampMs.value = notification.timestampMs
                runCatching {
                    when (notification.characteristic) {
                        Uuids.CHAR_ATTITUDE -> _attitude.value = json.decodeFromString<AttitudeFrame>(notification.bytes.decodeToString())
                        Uuids.CHAR_POWER -> _power.value = json.decodeFromString<PowerFrame>(notification.bytes.decodeToString())
                        Uuids.CHAR_ENVIRONMENT -> _environment.value = json.decodeFromString<EnvironmentFrame>(notification.bytes.decodeToString())
                        Uuids.CHAR_SYSTEM -> _system.value = json.decodeFromString<SystemFrame>(notification.bytes.decodeToString())
                        Uuids.CHAR_RESPONSE -> _commandResponses.tryEmit(json.decodeFromString<CommandResponse>(notification.bytes.decodeToString()))
                        Uuids.CHAR_EVENT -> _events.tryEmit(json.decodeFromString<SpacecraftEvent>(notification.bytes.decodeToString()))
                        else -> Unit
                    }
                }.onFailure {
                    _parseErrorCount.value += 1
                }
            }
        }
    }

    /** Clears cached telemetry on a fresh connection/reboot so stale values never linger. See docs/TEST_PLAN.md §4.8 / §8. */
    fun reset() {
        _attitude.value = null
        _power.value = null
        _environment.value = null
        _system.value = null
    }
}
