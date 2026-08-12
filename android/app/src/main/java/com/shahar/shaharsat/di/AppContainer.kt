package com.shahar.shaharsat.di

import android.content.Context
import com.shahar.shaharsat.ble.BleClient
import com.shahar.shaharsat.ble.CommandSender
import com.shahar.shaharsat.data.TelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal hand-written dependency container — no Hilt/KSP, per the
 * project's "simplicity over premature infrastructure" direction. A
 * single instance lives on [com.shahar.shaharsat.SatApplication] for the
 * process lifetime; ViewModels pull what they need from it.
 */
class AppContainer(context: Context) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob())
    val bleClient: BleClient = BleClient(context.applicationContext)
    val telemetryRepository: TelemetryRepository = TelemetryRepository(bleClient, appScope)
    val commandSender: CommandSender = CommandSender(bleClient, telemetryRepository.commandResponses, appScope)
}
