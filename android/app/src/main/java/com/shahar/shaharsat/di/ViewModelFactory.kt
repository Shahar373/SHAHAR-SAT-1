package com.shahar.shaharsat.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.shahar.shaharsat.ui.attitude.AttitudeViewModel
import com.shahar.shaharsat.ui.connection.ConnectionViewModel
import com.shahar.shaharsat.ui.dashboard.DashboardViewModel
import com.shahar.shaharsat.ui.debug.EngineeringViewModel

/** Hand-written factory matching [AppContainer] — no Hilt/KSP, see AppContainer.kt. */
class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        ConnectionViewModel::class.java -> ConnectionViewModel(container.bleClient) as T
        DashboardViewModel::class.java -> DashboardViewModel(container.telemetryRepository, container.commandSender, container.bleClient) as T
        EngineeringViewModel::class.java -> EngineeringViewModel(container.bleClient, container.telemetryRepository) as T
        AttitudeViewModel::class.java -> AttitudeViewModel(container.telemetryRepository, container.commandSender) as T
        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
