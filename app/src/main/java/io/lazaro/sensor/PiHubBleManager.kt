package io.lazaro.sensor

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("MissingPermission")
@Singleton
class PiHubBleManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        PiHubConnectionState(isBluetoothEnabled = adapter?.isEnabled == true),
    )
    val state: StateFlow<PiHubConnectionState> = _state.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedPiHubDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedPiHubDevice>> = _scannedDevices.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private val charMap = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val subscribedUuids = mutableSetOf<String>()
    private var statusPollJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName
            if (!PiHubProfile.matchesDeviceName(name)) return

            val scanned = ScannedPiHubDevice(
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

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected ${gatt.device.address}")
                    _state.update {
                        it.copy(
                            isConnected = true,
                            connectionLabel = "Conectado",
                            deviceAddress = gatt.device.address,
                            deviceName = gatt.device.name,
                        )
                    }
                    subscribedUuids.clear()
                    gatt.requestMtu(517)
                    gatt.readRemoteRssi()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Disconnected status=$status")
                    subscribedUuids.clear()
                    charMap.clear()
                    statusPollJob?.cancel()
                    _state.update {
                        it.copy(
                            isConnected = false,
                            connectionLabel = "Desconectado",
                            rssi = null,
                        )
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            charMap.clear()
            gatt.services.forEach { service ->
                service.characteristics.forEach { char ->
                    charMap["${service.uuid}|${char.uuid}"] = char
                }
            }
            subscribeNotifications()
            readStatus()
            startStatusPolling()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            processIncoming(characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            processIncoming(characteristic.uuid, value)
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
        subscribedUuids.clear()
        statusPollJob?.cancel()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        _state.update {
            it.copy(
                connectionLabel = "Conectando…",
                deviceAddress = address,
                deviceName = name ?: device.name,
            )
        }
    }

    fun connect(device: ScannedPiHubDevice) = connect(device.address, device.name)

    fun disconnect() {
        statusPollJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    fun readRssi() {
        gatt?.readRemoteRssi()
    }

    fun readStatus() {
        readCharacteristic(PiHubProfile.CHAR_STATUS)
    }

    fun readObjectsJson() {
        readCharacteristic(PiHubProfile.CHAR_OBJECTS_JSON)
    }

    fun requestVisionScan() {
        writeCharacteristic(PiHubProfile.CHAR_VISION_CMD, byteArrayOf(PiHubProfile.CMD_SCAN_NOW))
    }

    fun setVisionAutoInterval(sec: Int) {
        val clamped = sec.coerceIn(0, 120).toByte()
        writeCharacteristic(
            PiHubProfile.CHAR_VISION_CMD,
            byteArrayOf(PiHubProfile.CMD_AUTO_INTERVAL, clamped),
        )
    }

    private fun subscribeNotifications() {
        PiHubProfile.NOTIFY_CHARS.forEach { uuid ->
            enableNotifications(uuid)
        }
    }

    private fun enableNotifications(charUuid: UUID) {
        val key = charUuid.toString().lowercase()
        if (subscribedUuids.contains(key)) return
        val entry = charMap.entries.firstOrNull {
            it.key.endsWith("|$charUuid", ignoreCase = true)
        } ?: return
        val char = entry.value
        gatt?.setCharacteristicNotification(char, true)
        val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        char.getDescriptor(cccd)?.let { desc ->
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt?.writeDescriptor(desc)
            subscribedUuids += key
        }
    }

    private fun readCharacteristic(charUuid: UUID) {
        val entry = charMap.entries.firstOrNull {
            it.key.endsWith("|$charUuid", ignoreCase = true)
        } ?: return
        gatt?.readCharacteristic(entry.value)
    }

    private fun writeCharacteristic(charUuid: UUID, data: ByteArray) {
        val entry = charMap.entries.firstOrNull {
            it.key.endsWith("|$charUuid", ignoreCase = true)
        } ?: return
        val char = entry.value
        char.writeType = if (char.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        char.value = data
        gatt?.writeCharacteristic(char)
    }

    private fun startStatusPolling() {
        statusPollJob?.cancel()
        statusPollJob = scope.launch {
            while (isActive) {
                readStatus()
                readRssi()
                delay(STATUS_POLL_MS)
            }
        }
    }

    private fun processIncoming(charUuid: UUID, value: ByteArray) {
        when (charUuid.toString().lowercase()) {
            PiHubProfile.CHAR_DISTANCE.toString().lowercase() -> {
                PiHubProfile.parseDistanceCm(value)?.let { cm ->
                    _state.update { it.copy(distanceCm = cm) }
                }
            }
            PiHubProfile.CHAR_QUALITY.toString().lowercase() -> {
                PiHubProfile.parseQuality(value)?.let { q ->
                    _state.update { it.copy(quality = q) }
                }
            }
            PiHubProfile.CHAR_VISION_SUMMARY.toString().lowercase() -> {
                val summary = PiHubProfile.parseVisionSummary(value)
                if (summary.isNotBlank()) {
                    _state.update { it.copy(visionSummary = summary) }
                }
            }
            PiHubProfile.CHAR_OBJECTS_JSON.toString().lowercase() -> {
                val json = PiHubProfile.parseVisionSummary(value)
                if (json.isNotBlank()) {
                    _state.update { it.copy(objectsJson = json) }
                }
            }
            PiHubProfile.CHAR_STATUS.toString().lowercase() -> {
                _state.update {
                    it.copy(statusFlags = PiHubProfile.parseStatusFlags(value))
                }
            }
        }
    }

    companion object {
        private const val TAG = "PiHubBleManager"
        private const val STATUS_POLL_MS = 5_000L
    }
}
