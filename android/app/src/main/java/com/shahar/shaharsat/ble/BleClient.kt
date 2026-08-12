package com.shahar.shaharsat.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** One notification or read result arriving from the peripheral. */
data class BleNotification(val characteristic: UUID, val bytes: ByteArray, val timestampMs: Long)

/** One entry for the Engineering screen's raw packet log (see docs/TEST_PLAN.md §11). */
data class RawPacketLogEntry(
    val direction: Direction,
    val characteristic: UUID,
    val text: String,
    val timestampMs: Long
) {
    enum class Direction { RX, TX, ERROR }
}

/**
 * Owns the BLE connection lifecycle: permission/adapter checks, scanning
 * filtered by service UUID, GATT connect + service discovery + MTU
 * negotiation, subscribing to the four telemetry + two response/event
 * characteristics, and writing commands. Emits raw bytes only —
 * [com.shahar.shaharsat.data.TelemetryRepository] owns JSON parsing.
 *
 * Reconnection: on an unexpected disconnect, retries with bounded
 * exponential backoff (2s/4s/8s/16s/30s, [MAX_RECONNECT_ATTEMPTS]
 * attempts) rather than either a tight loop or Android's slow
 * background autoConnect whitelist. See docs/BLE_PROTOCOL.md §4 and
 * docs/TEST_PLAN.md §7.7.
 */
@SuppressLint("MissingPermission") // permission state is checked before every call site
class BleClient(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var reconnectAttempt = 0
    private var lastKnownAddress: String? = null
    private var userInitiatedDisconnect = false

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _linkInfo = MutableStateFlow(LinkInfo())
    val linkInfo: StateFlow<LinkInfo> = _linkInfo.asStateFlow()

    private val _notifications = MutableSharedFlow<BleNotification>(extraBufferCapacity = 64)
    val notifications: SharedFlow<BleNotification> = _notifications.asSharedFlow()

    private val _rawLog = MutableSharedFlow<RawPacketLogEntry>(extraBufferCapacity = 200)
    val rawLog: SharedFlow<RawPacketLogEntry> = _rawLog.asSharedFlow()

    private val notifyCharUuids = listOf(
        Uuids.CHAR_ATTITUDE, Uuids.CHAR_POWER, Uuids.CHAR_ENVIRONMENT,
        Uuids.CHAR_SYSTEM, Uuids.CHAR_RESPONSE, Uuids.CHAR_EVENT
    )

    @Suppress("DEPRECATION") // getParcelableExtra(String) — the typed overload is API 33+ only, minSdk here is 31
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (device?.address != lastKnownAddress) return
            val bonded = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_BONDED
            _linkInfo.update { it.copy(bonded = bonded) }
        }
    }

    init {
        // ACTION_BOND_STATE_CHANGED is a system-protected broadcast, but
        // targetSdk 33+ still requires an explicit export flag for any
        // dynamically registered receiver — ContextCompat handles the
        // pre/post-33 split.
        ContextCompat.registerReceiver(
            context, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Kicks off scan-then-connect. Safe to call repeatedly (e.g. a manual retry button). */
    fun start() {
        userInitiatedDisconnect = false
        if (!hasPermissions()) {
            _connectionState.value = ConnectionState.PermissionsRequired
            return
        }
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            _connectionState.value = ConnectionState.BluetoothOff
            return
        }
        reconnectAttempt = 0
        beginScan()
    }

    private fun beginScan() {
        val bt = adapter ?: return
        scanner = bt.bluetoothLeScanner
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Uuids.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        _connectionState.value = ConnectionState.Scanning
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            _connectionState.value = ConnectionState.PermissionsRequired
            return
        }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            stopScan()
            if (_connectionState.value is ConnectionState.Scanning) {
                _connectionState.value = ConnectionState.Idle
            }
        }
    }

    private fun stopScan() {
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) { /* adapter already off */ }
        scanTimeoutJob?.cancel()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            connectTo(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = ConnectionState.Error("Scan failed (code $errorCode)")
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        lastKnownAddress = device.address
        _connectionState.value = ConnectionState.Connecting(device.address)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempt = 0
                    g.requestMtu(BLE_PREFERRED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _linkInfo.update { it.copy(servicesDiscovered = false) }
                    val reason = if (status != BluetoothGatt.GATT_SUCCESS) "GATT status $status" else null
                    _connectionState.value = ConnectionState.Disconnected(reason)
                    g.close()
                    gatt = null
                    if (!userInitiatedDisconnect) scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            _linkInfo.update { it.copy(mtu = mtu) }
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Error("Service discovery failed (status $status)")
                return
            }
            val service = g.getService(Uuids.SERVICE)
            if (service == null) {
                _connectionState.value = ConnectionState.Error("SHAHAR-SAT service not found on device")
                return
            }
            for (uuid in notifyCharUuids) {
                val c = service.getCharacteristic(uuid) ?: continue
                g.setCharacteristicNotification(c, true)
                val cccd = c.getDescriptor(Uuids.CLIENT_CHARACTERISTIC_CONFIG) ?: continue
                writeDescriptorEnableNotify(g, cccd)
            }
            _linkInfo.update { it.copy(deviceAddress = g.device.address, servicesDiscovered = true) }
            _connectionState.value = ConnectionState.Connected(g.device.address, g.device.name)
            readRssi()
        }

        @Suppress("DEPRECATION") // required override for API < 33 (minSdk 31 must still work on Android 12/12L)
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            emitNotification(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            emitNotification(characteristic.uuid, value)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _linkInfo.update { it.copy(rssi = rssi) }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _rawLog.tryEmit(RawPacketLogEntry(
                    RawPacketLogEntry.Direction.ERROR, characteristic.uuid,
                    "write failed, status=$status", System.currentTimeMillis()
                ))
            }
        }
    }

    private fun emitNotification(uuid: UUID, bytes: ByteArray) {
        val now = System.currentTimeMillis()
        _notifications.tryEmit(BleNotification(uuid, bytes, now))
        _rawLog.tryEmit(RawPacketLogEntry(RawPacketLogEntry.Direction.RX, uuid, bytes.decodeToString(), now))
    }

    private fun writeDescriptorEnableNotify(g: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }

    /** Returns false immediately if not connected — callers must not queue commands while disconnected. */
    fun sendCommand(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val service = g.getService(Uuids.SERVICE) ?: return false
        val characteristic = service.getCharacteristic(Uuids.CHAR_COMMAND) ?: return false

        val now = System.currentTimeMillis()
        _rawLog.tryEmit(RawPacketLogEntry(RawPacketLogEntry.Direction.TX, Uuids.CHAR_COMMAND, bytes.decodeToString(), now))

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
    }

    fun readRssi() {
        gatt?.readRemoteRssi()
    }

    /** User-initiated disconnect — no reconnect attempt follows. */
    fun disconnect() {
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        stopScan()
        gatt?.disconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Idle
            return
        }
        val delayMs = RECONNECT_DELAYS_MS.getOrElse(reconnectAttempt) { RECONNECT_DELAYS_MS.last() }
        reconnectAttempt++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!userInitiatedDisconnect) beginScan()
        }
    }

    fun shutdown() {
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        stopScan()
        gatt?.close()
        gatt = null
        try { context.unregisterReceiver(bondReceiver) } catch (_: IllegalArgumentException) { /* not registered */ }
        scope.cancel()
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val BLE_PREFERRED_MTU = 247
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val RECONNECT_DELAYS_MS = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    }
}
