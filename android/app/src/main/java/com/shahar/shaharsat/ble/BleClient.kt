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
 * A single BLE GATT operation to run on this connection. Android's
 * BluetoothGatt allows exactly one outstanding operation at a time —
 * issuing a second `writeDescriptor`/`writeCharacteristic`/etc. before the
 * previous one's callback has fired causes the second (and any further)
 * call to silently fail. [GattOperationQueue] exists so nothing in this
 * file ever violates that rule. See docs/ARCHITECTURE.md's Android
 * section and docs/TEST_PLAN.md §3/§11.
 */
private sealed interface GattOp {
    data class RequestMtu(val mtu: Int) : GattOp
    data object DiscoverServices : GattOp
    data class EnableNotification(val charUuid: UUID) : GattOp
    data class WriteCharacteristic(val charUuid: UUID, val bytes: ByteArray) : GattOp
    data class ReadCharacteristic(val charUuid: UUID) : GattOp
}

/**
 * Serializes GATT operations on a single [BluetoothGatt] connection into a
 * strict one-at-a-time queue: enqueue, dispatch, wait for the matching
 * callback (or a timeout), dispatch the next one. Deliberately a plain
 * FIFO queue + timeout, not a general async framework — this only needs
 * to handle the five operation kinds in [GattOp].
 *
 * A stuck operation (callback never fires — e.g. the link drops mid-op)
 * times out after [OP_TIMEOUT_MS] and the queue moves on, rather than
 * wedging every subsequent operation forever.
 */
private class GattOperationQueue(
    private val scope: CoroutineScope,
    private val onOpFailed: (GattOp, String) -> Unit
) {
    private val pending = ArrayDeque<GattOp>()
    private var inFlight: GattOp? = null
    private var inFlightToken = 0L
    private var timeoutJob: Job? = null
    private var dispatcher: ((GattOp) -> Boolean)? = null

    /** Must be called once a live [BluetoothGatt] is available; cleared on disconnect. */
    fun attach(dispatch: (GattOp) -> Boolean) {
        dispatcher = dispatch
    }

    fun detach() {
        dispatcher = null
        pending.clear()
        inFlight = null
        timeoutJob?.cancel()
    }

    fun enqueue(op: GattOp) {
        pending.addLast(op)
        processNext()
    }

    private fun processNext() {
        if (inFlight != null) return
        val op = pending.removeFirstOrNull() ?: return
        val dispatch = dispatcher
        if (dispatch == null) {
            onOpFailed(op, "no active GATT connection")
            processNext()
            return
        }
        inFlight = op
        val token = ++inFlightToken
        val accepted = try { dispatch(op) } catch (e: SecurityException) { false }
        if (!accepted) {
            complete(token, false, "dispatch rejected (permission or GATT busy)")
            return
        }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(OP_TIMEOUT_MS)
            complete(token, false, "timed out waiting for callback")
        }
    }

    /** Call from the matching GATT callback (onMtuChanged, onServicesDiscovered, onDescriptorWrite, ...). */
    fun complete(token: Long, success: Boolean, errorMessage: String? = null) {
        if (token != inFlightToken) return // stale callback or already timed out
        val op = inFlight ?: return
        timeoutJob?.cancel()
        inFlight = null
        if (!success) onOpFailed(op, errorMessage ?: "failed")
        processNext()
    }

    /** The token for the operation currently in flight, for callbacks to pass back to [complete]. */
    val currentToken: Long get() = inFlightToken

    companion object {
        private const val OP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Owns the BLE connection lifecycle: permission/adapter checks, scanning
 * filtered by service UUID, GATT connect + a serialized setup sequence
 * (MTU -> service discovery -> six notification subscriptions), and
 * writing commands. Emits raw bytes only —
 * [com.shahar.shaharsat.data.TelemetryRepository] owns JSON parsing.
 *
 * Bonding: per docs/BLE_PROTOCOL.md §8, PING/GET_STATUS work before
 * pairing, so reaching [ConnectionState.Connected] does not wait on a
 * bond. Instead, once setup finishes, [BleClient] proactively calls
 * `createBond()` in the background (unless already bonded) so pairing
 * happens up front rather than being deferred to the first authenticated
 * command. Bonding progress is tracked in [LinkInfo], not as its own
 * [ConnectionState] — it's orthogonal to connection state in this
 * design, not a phase that blocks reaching Connected.
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
    private var pendingNotifySetup = 0

    private val opQueue = GattOperationQueue(scope) { op, message ->
        val uuid = when (op) {
            is GattOp.RequestMtu -> null
            GattOp.DiscoverServices -> null
            is GattOp.EnableNotification -> op.charUuid
            is GattOp.WriteCharacteristic -> op.charUuid
            is GattOp.ReadCharacteristic -> op.charUuid
        }
        val now = System.currentTimeMillis()
        _linkInfo.update { it.copy(lastError = "$op: $message") }
        _rawLog.tryEmit(RawPacketLogEntry(RawPacketLogEntry.Direction.ERROR, uuid ?: Uuids.SERVICE, "$op failed: $message", now))
        // A failed EnableNotification during setup still counts toward
        // finishing the setup sequence — one missing/broken characteristic
        // must not block the others or leave the connection stuck pending.
        if (op is GattOp.EnableNotification) onNotifySetupStepDone()
    }

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
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDING -> _linkInfo.update { it.copy(bonding = true) }
                BluetoothDevice.BOND_BONDED -> _linkInfo.update { it.copy(bonding = false, bonded = true) }
                BluetoothDevice.BOND_NONE -> _linkInfo.update { it.copy(bonding = false, bonded = false) }
            }
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
                    opQueue.attach { op -> dispatch(g, op) }
                    opQueue.enqueue(GattOp.RequestMtu(BLE_PREFERRED_MTU))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    opQueue.detach()
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
            val token = opQueue.currentToken
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _linkInfo.update { it.copy(mtu = mtu) }
                opQueue.enqueue(GattOp.DiscoverServices)
                opQueue.complete(token, true)
            } else {
                opQueue.complete(token, false, "MTU negotiation failed, status=$status")
                // Still worth trying to discover services on the default MTU.
                opQueue.enqueue(GattOp.DiscoverServices)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val token = opQueue.currentToken
            if (status != BluetoothGatt.GATT_SUCCESS) {
                opQueue.complete(token, false, "service discovery failed, status=$status")
                _connectionState.value = ConnectionState.Error("Service discovery failed (status $status)")
                return
            }
            val service = g.getService(Uuids.SERVICE)
            if (service == null) {
                opQueue.complete(token, false, "SHAHAR-SAT service not present")
                _connectionState.value = ConnectionState.Error("SHAHAR-SAT service not found on device")
                return
            }

            val presentUuids = notifyCharUuids.filter { service.getCharacteristic(it) != null }
            pendingNotifySetup = presentUuids.size
            if (pendingNotifySetup == 0) {
                opQueue.complete(token, false, "no known characteristics present")
                _connectionState.value = ConnectionState.Error("No SHAHAR-SAT characteristics found")
                return
            }
            presentUuids.forEach { opQueue.enqueue(GattOp.EnableNotification(it)) }
            _linkInfo.update { it.copy(deviceAddress = g.device.address) }
            opQueue.complete(token, true)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Only the success path calls onNotifySetupStepDone() here — on
            // failure, opQueue.complete() below already triggers it exactly
            // once via the onOpFailed callback passed to GattOperationQueue.
            // Calling it again here too would double-decrement
            // pendingNotifySetup and fire ConnectionState.Connected one
            // characteristic early.
            val token = opQueue.currentToken
            if (status == BluetoothGatt.GATT_SUCCESS) {
                opQueue.complete(token, true)
                onNotifySetupStepDone()
            } else {
                opQueue.complete(token, false, "descriptor write failed, status=$status")
            }
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
            val token = opQueue.currentToken
            opQueue.complete(token, status == BluetoothGatt.GATT_SUCCESS, "write failed, status=$status")
        }

        @Suppress("DEPRECATION") // required override for API < 33 (minSdk 31 must still work on Android 12/12L) — same reasoning as onCharacteristicChanged above
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val token = opQueue.currentToken
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                emitNotification(characteristic.uuid, characteristic.value ?: ByteArray(0))
            }
            opQueue.complete(token, status == BluetoothGatt.GATT_SUCCESS, "read failed, status=$status")
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val token = opQueue.currentToken
            if (status == BluetoothGatt.GATT_SUCCESS) {
                emitNotification(characteristic.uuid, value)
            }
            opQueue.complete(token, status == BluetoothGatt.GATT_SUCCESS, "read failed, status=$status")
        }
    }

    /**
     * One EnableNotification finished (success, failure, or timeout) during
     * initial setup. Once all of them have, the connection is genuinely
     * usable — this is what actually flips [ConnectionState] to
     * [ConnectionState.Connected], not service discovery finishing.
     */
    private fun onNotifySetupStepDone() {
        if (pendingNotifySetup <= 0) return
        pendingNotifySetup--
        if (pendingNotifySetup == 0) {
            val g = gatt ?: return
            _linkInfo.update { it.copy(servicesDiscovered = true) }
            _connectionState.value = ConnectionState.Connected(g.device.address, g.device.name)
            readRssi()
            ensureBonded(g.device)
        }
    }

    /** Proactively starts pairing if not already bonded — see the class doc on why this isn't a [ConnectionState]. */
    private fun ensureBonded(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            try { device.createBond() } catch (_: SecurityException) { /* permission revoked mid-session */ }
        } else if (device.bondState == BluetoothDevice.BOND_BONDED) {
            _linkInfo.update { it.copy(bonded = true) }
        }
    }

    /** Performs the actual platform call for one queued operation. Returns false if it couldn't even be issued. */
    private fun dispatch(g: BluetoothGatt, op: GattOp): Boolean = when (op) {
        is GattOp.RequestMtu -> g.requestMtu(op.mtu)

        GattOp.DiscoverServices -> g.discoverServices()

        is GattOp.EnableNotification -> {
            val service = g.getService(Uuids.SERVICE)
            val characteristic = service?.getCharacteristic(op.charUuid)
            val cccd = characteristic?.getDescriptor(Uuids.CLIENT_CHARACTERISTIC_CONFIG)
            if (characteristic == null || cccd == null) {
                false
            } else {
                g.setCharacteristicNotification(characteristic, true)
                writeDescriptorEnableNotify(g, cccd)
            }
        }

        is GattOp.WriteCharacteristic -> {
            val service = g.getService(Uuids.SERVICE)
            val characteristic = service?.getCharacteristic(op.charUuid)
            if (characteristic == null) {
                false
            } else {
                val now = System.currentTimeMillis()
                _rawLog.tryEmit(RawPacketLogEntry(RawPacketLogEntry.Direction.TX, op.charUuid, op.bytes.decodeToString(), now))
                writeCharacteristicBytes(g, characteristic, op.bytes)
            }
        }

        is GattOp.ReadCharacteristic -> {
            val service = g.getService(Uuids.SERVICE)
            val characteristic = service?.getCharacteristic(op.charUuid)
            if (characteristic == null) false else g.readCharacteristic(characteristic)
        }
    }

    private fun emitNotification(uuid: UUID, bytes: ByteArray) {
        val now = System.currentTimeMillis()
        _notifications.tryEmit(BleNotification(uuid, bytes, now))
        _rawLog.tryEmit(RawPacketLogEntry(RawPacketLogEntry.Direction.RX, uuid, bytes.decodeToString(), now))
    }

    private fun writeDescriptorEnableNotify(g: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }

    private fun writeCharacteristicBytes(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
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

    /**
     * Enqueues the command write and returns immediately. `true` only
     * means "there is a connection to queue this on" — it is not a
     * synchronous success/failure of the BLE write itself, which is
     * inherently asynchronous. Callers (see [CommandSender]) already wait
     * for the Response-characteristic notification with their own
     * timeout, which is what actually confirms the round trip.
     */
    fun sendCommand(bytes: ByteArray): Boolean {
        if (gatt == null) return false
        opQueue.enqueue(GattOp.WriteCharacteristic(Uuids.CHAR_COMMAND, bytes))
        return true
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
        opQueue.detach()
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
