package com.shahar.shaharsat.ui.attitude

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shahar.shaharsat.ble.CommandSender
import com.shahar.shaharsat.data.AttitudeFrame
import com.shahar.shaharsat.data.Command
import com.shahar.shaharsat.data.TelemetryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Attitude passthrough (the quaternion math lives in gl/Quaternion.kt) plus the ZERO_ATTITUDE control. */
class AttitudeViewModel(
    telemetryRepository: TelemetryRepository,
    private val commandSender: CommandSender
) : ViewModel() {
    val attitude: StateFlow<AttitudeFrame?> = telemetryRepository.attitude

    private val _zeroing = MutableStateFlow(false)
    val zeroing: StateFlow<Boolean> = _zeroing.asStateFlow()

    fun zeroAttitude() {
        if (_zeroing.value) return
        _zeroing.value = true
        viewModelScope.launch {
            commandSender.send(Command.ZERO_ATTITUDE)
            _zeroing.value = false
        }
    }
}
