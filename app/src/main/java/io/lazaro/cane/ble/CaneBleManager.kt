package io.lazaro.cane.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.cane.CaneConnectionState
import io.lazaro.cane.CaneHandshakeState
import io.lazaro.cane.ScannedCaneDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
@Singleton
class CaneBleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val handshakeCapture: CaneHandshakeCapture,
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        CaneConnectionState(isBluetoothEnabled = adapter?.isEnabled == true)
    )
    val state: StateFlow<CaneConnectionState> = _state.asStateFlow()

    private val _bleEvents = MutableSharedFlow<CaneBleEvent>(extraBufferCapacity = 64)
    val bleEvents: SharedFlow<CaneBleEvent> = _bleEvents.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private val charMap = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val subscribedUuids = mutableSetOf<String>()
    private var handshakeJob: Job? = null
    private var writeContinuation: ((Boolean) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName
            if (!WeWalkDevice.matchesDeviceName(name)) return

            val scanned = ScannedCaneDevice(
                address = device.address,
                name = name,
                rssi = result.rssi,
            )
            val current = _scannedDevices.value.toMutableList()
            val idx = current.indexOfFirst { it.address == scanned.address }
            if (idx >= 0) current[idx] = scanned else current.add(scanned)
            current.sortByDescending { it.rssi }
            _scannedDevices.value = current
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _state.update { it.copy(isScanning = false) }
        }
    }

    private val _scannedDevices = MutableStateFlow<List<ScannedCaneDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedCaneDevice>> = _scannedDevices.asStateFlow()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected ${gatt.device.address}")
                    if (handshakeCapture.isActive()) {
                        handshakeCapture.info(note = "GATT conectado status=$status")
                    }
                    handshakeJob?.cancel()
                    subscribedUuids.clear()
                    _state.update {
                        it.copy(
                            isConnected = true,
                            connectionLabel = "Conectado",
                            deviceAddress = gatt.device.address,
                            deviceName = gatt.device.name,
                            handshakeState = CaneHandshakeState.PENDING,
                            handshakeDetail = null,
                        )
                    }
                    gatt.requestMtu(517)
                    gatt.readRemoteRssi()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Disconnected status=$status")
                    if (handshakeCapture.isActive()) {
                        handshakeCapture.info(note = "GATT desconectado status=$status")
                    }
                    handshakeJob?.cancel()
                    subscribedUuids.clear()
                    charMap.clear()
                    _state.update {
                        it.copy(
                            isConnected = false,
                            connectionLabel = "Desconectado",
                            batteryPercent = null,
                            handshakeState = CaneHandshakeState.PENDING,
                            handshakeDetail = null,
                        )
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (handshakeCapture.isActive()) {
                handshakeCapture.info(
                    note = "Servicios descubiertos status=$status count=${gatt.services.size}",
                )
            }
            charMap.clear()
            val available = mutableSetOf<String>()
            gatt.services.forEach { service ->
                service.characteristics.forEach { char ->
                    val uuid = char.uuid.toString().lowercase()
                    available += uuid
                    charMap["${service.uuid}|${char.uuid}"] = char
                }
            }
            subscribeAllNotify(available)
            readBattery()
            startHandshake()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            processIncoming(characteristic.uuid.toString(), value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            if (!ok) {
                Log.w(TAG, "Write failed ${characteristic.uuid} status=$status")
                if (handshakeCapture.isActive()) {
                    handshakeCapture.info(
                        charUuid = characteristic.uuid.toString(),
                        note = "Write falló status=$status",
                    )
                }
            }
            writeContinuation?.invoke(ok)
            writeContinuation = null
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            processIncoming(characteristic.uuid.toString(), value)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _state.update { it.copy(rssi = rssi) }
            }
        }
    }

    fun refreshBluetoothState() {
        _state.update { it.copy(isBluetoothEnabled = adapter?.isEnabled == true) }
    }

    fun startScan() {
        val bt = adapter ?: return
        if (!bt.isEnabled) return
        _scannedDevices.value = emptyList()
        _state.update { it.copy(isScanning = true) }
        bt.bluetoothLeScanner.startScan(scanCallback)
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _state.update { it.copy(isScanning = false) }
    }

    fun connect(address: String, name: String? = null) {
        stopScan()
        val device = adapter?.getRemoteDevice(address) ?: return
        gatt?.close()
        handshakeJob?.cancel()
        subscribedUuids.clear()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        _state.update {
            it.copy(
                connectionLabel = "Conectando…",
                deviceAddress = address,
                deviceName = name ?: device.name,
                handshakeState = CaneHandshakeState.PENDING,
                handshakeDetail = null,
            )
        }
    }

    fun connect(device: ScannedCaneDevice) = connect(device.address, device.name)

    fun disconnect() {
        handshakeJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    fun readRssi() {
        gatt?.readRemoteRssi()
    }

    /** Reenvía la secuencia de handshake (útil si los botones no responden). */
    fun retryHandshake() {
        if (!_state.value.isConnected) return
        startHandshake()
    }

    fun startHandshakeCapture() {
        val state = _state.value
        handshakeCapture.start(state.deviceName, state.deviceAddress)
    }

    fun stopHandshakeCapture() {
        handshakeCapture.stop()
    }

    fun isHandshakeCapturing(): Boolean = handshakeCapture.isActive()

    fun handshakeCaptureCount(): Int = handshakeCapture.entryCount.value

    private fun startHandshake() {
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            _state.update {
                it.copy(
                    handshakeState = CaneHandshakeState.IN_PROGRESS,
                    handshakeDetail = "Preparando canales…",
                )
            }
            delay(NOTIFY_SETTLE_MS)
            enableHidReportMode()
            delay(WRITE_GAP_MS)
            readHidCharacteristics()

            var failures = 0
            for (step in WeWalkHandshake.buildSequence()) {
                if (!charExists(step.charUuid)) {
                    Log.d(TAG, "Handshake skip (no char): ${step.label}")
                    continue
                }
                _state.update { it.copy(handshakeDetail = step.label) }
                val ok = writeBytes(step.charUuid, step.data)
                if (!ok) {
                    failures++
                    Log.w(TAG, "Handshake falló: ${step.label}")
                } else {
                    Log.i(TAG, "Handshake TX: ${step.label}")
                }
                delay(step.delayAfterMs)
            }

            val finalState = if (failures >= WeWalkHandshake.buildSequence().size) {
                CaneHandshakeState.FAILED
            } else {
                CaneHandshakeState.READY
            }
            _state.update {
                it.copy(
                    handshakeState = finalState,
                    handshakeDetail = when (finalState) {
                        CaneHandshakeState.READY ->
                            "Protocolo enviado. Pulsa un botón del bastón."
                        CaneHandshakeState.FAILED ->
                            "Handshake incompleto. Cierra la app oficial WeWALK e inténtalo de nuevo."
                        else -> it.handshakeDetail
                    },
                )
            }
        }
    }

    private fun charExists(charUuid: String): Boolean {
        return charMap.keys.any { it.endsWith("|$charUuid", ignoreCase = true) }
    }

    private suspend fun writeBytes(charUuid: String, data: ByteArray): Boolean {
        val entry = charMap.entries.firstOrNull {
            it.key.endsWith("|$charUuid", ignoreCase = true)
        } ?: return false
        val char = entry.value
        val g = gatt ?: return false
        val useNoResponse = char.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 &&
            char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
        char.writeType = if (useNoResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        char.value = data
        if (handshakeCapture.isActive()) {
            handshakeCapture.recordTx(
                charUuid = charUuid,
                data = data,
                label = WeWalkDevice.labelForUuid(charUuid),
                note = "handshake TX",
            )
        }
        return suspendCancellableCoroutine { cont ->
            writeContinuation = { ok -> cont.resume(ok) }
            cont.invokeOnCancellation { writeContinuation = null }
            if (!g.writeCharacteristic(char)) {
                writeContinuation = null
                cont.resume(false)
            }
        }
    }

    private fun enableHidReportMode() {
        val info = charMap.entries.firstOrNull {
            it.key.endsWith("|${WeWalkDevice.CHAR_HID_PROTOCOL_MODE}", ignoreCase = true)
        } ?: return
        val char = info.value
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        char.value = byteArrayOf(0x01)
        gatt?.writeCharacteristic(char)
    }

    private fun readHidCharacteristics() {
        listOf(
            WeWalkDevice.CHAR_HID_REPORT,
            WeWalkDevice.CHAR_HID_BOOT_KB,
            WeWalkDevice.CHAR_HID_REPORT_MAP,
        ).forEach { uuid ->
            charMap.entries.firstOrNull {
                it.key.endsWith("|$uuid", ignoreCase = true)
            }?.value?.let { gatt?.readCharacteristic(it) }
        }
    }

    private fun subscribeAllNotify(available: Set<String>) {
        WeWalkDevice.NOTIFY_CANDIDATES.forEach { candidate ->
            if (available.contains(candidate.lowercase())) {
                enableNotifications(candidate)
            }
        }
    }

    private fun enableNotifications(charUuid: String) {
        if (subscribedUuids.contains(charUuid.lowercase())) return
        val entry = charMap.entries.firstOrNull {
            it.key.endsWith("|$charUuid", ignoreCase = true)
        } ?: return
        val char = entry.value
        gatt?.setCharacteristicNotification(char, true)
        val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        char.getDescriptor(cccd)?.let { desc ->
            val enableValue = if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            desc.value = enableValue
            gatt?.writeDescriptor(desc)
            subscribedUuids += charUuid.lowercase()
            if (handshakeCapture.isActive()) {
                handshakeCapture.info(
                    charUuid = charUuid,
                    note = "NOTIFY ON ${WeWalkDevice.labelForUuid(charUuid) ?: charUuid}",
                )
            }
        }
    }

    private fun readBattery() {
        val entry = charMap.entries.firstOrNull {
            it.key.endsWith("|${WeWalkDevice.CHAR_BATTERY}", ignoreCase = true)
        } ?: return
        gatt?.readCharacteristic(entry.value)
    }

    private fun processIncoming(charUuid: String, value: ByteArray) {
        val hex = value.joinToString(" ") { "%02X".format(it) }
        handshakeCapture.recordRx(
            charUuid = charUuid,
            data = value,
            label = WeWalkDevice.labelForUuid(charUuid),
        )
        when (charUuid.lowercase()) {
            WeWalkDevice.CHAR_BATTERY.lowercase() -> {
                val pct = value.firstOrNull()?.toInt()?.and(0xFF)
                if (pct != null) _state.update { it.copy(batteryPercent = pct) }
            }
        }

        WeWalkProtocol.parseFrame(value)?.let { frame ->
            val desc = WeWalkProtocol.describePayload(frame.cmd, frame.payload)
            Log.i(TAG, "PROTO RX: $desc")
            _state.update {
                it.copy(
                    handshakeDetail = "RX: $desc",
                    lastEventHex = frame.raw.joinToString(" ") { b -> "%02X".format(b) },
                    lastEventLabel = "PROTO 0x${frame.cmd.toString(16).uppercase()}",
                )
            }
            if (frame.cmd == WeWalkProtocol.CMD_BATTERY) {
                frame.payload.firstOrNull()?.toInt()?.and(0xFF)?.let { pct ->
                    _state.update { it.copy(batteryPercent = pct) }
                }
            }
        }

        if (!WeWalkDevice.isMeaningfulPayload(hex)) return

        val label = when {
            charUuid.equals(WeWalkDevice.CHAR_HID_REPORT, ignoreCase = true) ||
                charUuid.equals(WeWalkDevice.CHAR_HID_BOOT_KB, ignoreCase = true) ->
                WeWalkDevice.describeHidReport(value)
            else -> WeWalkDevice.labelForUuid(charUuid)
        }

        _state.update {
            it.copy(
                lastEventHex = hex,
                lastEventLabel = label,
            )
        }
        _bleEvents.tryEmit(
            CaneBleEvent(
                charUuid = charUuid,
                hexPayload = hex,
                channelLabel = label,
            )
        )
    }

    companion object {
        private const val TAG = "CaneBleManager"
        private const val NOTIFY_SETTLE_MS = 900L
        private const val WRITE_GAP_MS = 200L
    }
}
