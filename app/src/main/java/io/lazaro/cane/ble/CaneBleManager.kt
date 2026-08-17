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
import io.lazaro.cane.CaneObstacleAlertManager
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
@Singleton
class CaneBleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val handshakeCapture: CaneHandshakeCapture,
    private val caneObstacleAlertManager: CaneObstacleAlertManager,
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
    private var obstaclePollJob: Job? = null
    private var reconnectJob: Job? = null
    private var writeContinuation: ((Boolean) -> Unit)? = null
    private var descriptorContinuation: ((Boolean) -> Unit)? = null

    @Volatile
    private var autoReconnectEnabled = false

    @Volatile
    private var reconnectMac: String? = null

    @Volatile
    private var reconnectName: String? = null

    @Volatile
    private var reconnectAttempt = 0

    @Volatile
    private var sleepBlocked = false

    /** Evita dependencia circular con Hilt; el FGS lo cablea. */
    @Volatile
    private var statusCue: io.lazaro.assistant.BlindStatusSpeaker? = null

    fun bindStatusSpeaker(speaker: io.lazaro.assistant.BlindStatusSpeaker) {
        statusCue = speaker
    }

    fun setSleepBlocked(blocked: Boolean) {
        sleepBlocked = blocked
        if (blocked) {
            reconnectJob?.cancel()
            reconnectJob = null
        } else if (autoReconnectEnabled && !_state.value.isConnected) {
            scheduleAutoReconnect(announceDisconnect = false)
        }
    }

    /**
     * Activa reintentos con backoff mientras haya MAC y el asistente esté activo.
     */
    fun enableAutoReconnect(mac: String, name: String?) {
        reconnectMac = mac
        reconnectName = name
        autoReconnectEnabled = true
        if (!_state.value.isConnected && !sleepBlocked) {
            scheduleAutoReconnect(announceDisconnect = false)
        }
    }

    fun disableAutoReconnect() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
    }

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
                    reconnectJob?.cancel()
                    reconnectAttempt = 0
                    subscribedUuids.clear()
                    val announceReady = autoReconnectEnabled && !_state.value.isConnected
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
                    // Samsung engancha HID Host y se queda con los botones; soltarlo.
                    releaseSystemHidHost(gatt.device)
                    gatt.requestMtu(517)
                    gatt.readRemoteRssi()
                    if (announceReady) {
                        scope.launch {
                            statusCue?.announceCaneReady()
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Disconnected status=$status")
                    if (handshakeCapture.isActive()) {
                        handshakeCapture.info(note = "GATT desconectado status=$status")
                    }
                    handshakeJob?.cancel()
                    stopObstaclePoll()
                    subscribedUuids.clear()
                    charMap.clear()
                    val wasConnected = _state.value.isConnected
                    _state.update {
                        it.copy(
                            isConnected = false,
                            connectionLabel = "Desconectado",
                            batteryPercent = null,
                            handshakeState = CaneHandshakeState.PENDING,
                            handshakeDetail = null,
                        )
                    }
                    if (wasConnected || autoReconnectEnabled) {
                        scheduleAutoReconnect(announceDisconnect = wasConnected)
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
            handshakeJob?.cancel()
            handshakeJob = scope.launch {
                subscribeAllNotify(available)
                runHandshake()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            if (!ok) {
                Log.w(TAG, "Descriptor write failed ${descriptor.characteristic.uuid} status=$status")
            }
            descriptorContinuation?.invoke(ok)
            descriptorContinuation = null
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

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
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
        connectInternal(address, name, autoConnect = false)
    }

    fun connect(device: ScannedCaneDevice) = connect(device.address, device.name)

    private fun connectInternal(address: String, name: String?, autoConnect: Boolean) {
        stopScan()
        val device = adapter?.getRemoteDevice(address) ?: return
        gatt?.close()
        handshakeJob?.cancel()
        stopObstaclePoll()
        subscribedUuids.clear()
        gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        _state.update {
            it.copy(
                connectionLabel = if (autoConnect) "Reconectando…" else "Conectando…",
                deviceAddress = address,
                deviceName = name ?: device.name,
                handshakeState = CaneHandshakeState.PENDING,
                handshakeDetail = null,
            )
        }
    }

    private fun scheduleAutoReconnect(announceDisconnect: Boolean) {
        if (!autoReconnectEnabled || sleepBlocked) return
        val mac = reconnectMac ?: _state.value.deviceAddress ?: return
        reconnectMac = mac
        if (announceDisconnect) {
            scope.launch { statusCue?.announceCaneDisconnected() }
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (isActive && autoReconnectEnabled && !sleepBlocked && !_state.value.isConnected) {
                val delayMs = BACKOFF_MS[reconnectAttempt.coerceAtMost(BACKOFF_MS.lastIndex)]
                Log.i(TAG, "Auto-reconnect attempt=${reconnectAttempt + 1} in ${delayMs}ms → $mac")
                delay(delayMs)
                if (!autoReconnectEnabled || sleepBlocked || _state.value.isConnected) break
                val useAuto = reconnectAttempt > 0
                reconnectAttempt++
                try {
                    connectInternal(mac, reconnectName, autoConnect = useAuto)
                } catch (e: Exception) {
                    Log.w(TAG, "Auto-reconnect falló: ${e.message}")
                }
            }
        }
    }

    fun disconnect() {
        disableAutoReconnect()
        handshakeJob?.cancel()
        stopObstaclePoll()
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
        handshakeJob?.cancel()
        handshakeJob = scope.launch { runHandshake() }
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

    private suspend fun runHandshake() {
        _state.update {
            it.copy(
                handshakeState = CaneHandshakeState.IN_PROGRESS,
                handshakeDetail = "Preparando canal de botones…",
            )
        }
        delay(NOTIFY_SETTLE_MS)

        // Samsung: Report HID (2A4D) es PRIVILEGED. Forzar Boot Protocol → 2A22 usable.
        _state.update { it.copy(handshakeDetail = "HID Boot mode") }
        enableHidBootMode()
        delay(200)
        subscribedUuids.remove(WeWalkDevice.CHAR_HID_BOOT_KB.lowercase())
        try {
            enableNotifications(WeWalkDevice.CHAR_HID_BOOT_KB)
        } catch (e: Exception) {
            Log.w(TAG, "NOTIFY Boot KB: ${e.message}")
        }

        // Activar sesión app (P2P fe42, best-effort).
        var txOk = false
        if (charExists(WeWalkDevice.CHAR_TX_FE43)) {
            val steps = listOf(
                "Consulta batería P2P" to WeWalkProtocol.buildFrame(WeWalkProtocol.CMD_BATTERY),
                "Inicio sesión P2P" to WeWalkProtocol.buildFrame(WeWalkProtocol.CMD_INIT),
                "Activar sesión app" to WeWalkProtocol.buildFrame(WeWalkProtocol.CMD_SESSION, byteArrayOf(0x02)),
            )
            for ((label, data) in steps) {
                _state.update { it.copy(handshakeDetail = label) }
                val ok = writeBytes(WeWalkDevice.CHAR_TX_FE43, data)
                if (ok) {
                    txOk = true
                    Log.i(TAG, "Handshake TX: $label")
                } else {
                    Log.w(TAG, "Handshake falló: $label")
                }
                delay(200)
            }
        }

        val p2pReady = subscribedUuids.contains(WeWalkDevice.CHAR_RX_FE42.lowercase())
        val hidReady = subscribedUuids.contains(WeWalkDevice.CHAR_HID_BOOT_KB.lowercase())
        if (p2pReady) {
            subscribedUuids.remove(WeWalkDevice.CHAR_RX_FE42.lowercase())
            try {
                enableNotifications(WeWalkDevice.CHAR_RX_FE42)
            } catch (e: Exception) {
                Log.w(TAG, "Re-NOTIFY fe42: ${e.message}")
            }
        }
        val stillP2p = subscribedUuids.contains(WeWalkDevice.CHAR_RX_FE42.lowercase())
        val stillHid = subscribedUuids.contains(WeWalkDevice.CHAR_HID_BOOT_KB.lowercase())
        val finalState = if (stillP2p || stillHid || hidReady) {
            CaneHandshakeState.READY
        } else {
            CaneHandshakeState.FAILED
        }
        _state.update {
            it.copy(
                handshakeState = finalState,
                handshakeDetail = when {
                    finalState != CaneHandshakeState.READY ->
                        "No se pudo activar botones. Cierra WeWALK/nRF e inténtalo."
                    stillHid || hidReady ->
                        "Canal HID Boot listo. Pulsa un botón del bastón."
                    else ->
                        "Canal de botones listo. Pulsa un botón del bastón."
                },
            )
        }
        Log.i(TAG, "Handshake listo p2p=$stillP2p hidBoot=$stillHid batteryTx=$txOk")

        // Tras sesión: sondeo + poll periódico (status fe45 llega ~10 s si no pedimos).
        if (txOk && finalState == CaneHandshakeState.READY) {
            probeObstacleTelemetry()
            startObstaclePoll()
        }
    }

    /**
     * Probes seguros post-sesión: re-sesión + C1/CA.
     * Las respuestas (si existen) salen por NOTIFY y se etiquetan RX OBSTACLE?.
     */
    private suspend fun probeObstacleTelemetry() {
        if (!charExists(WeWalkDevice.CHAR_TX_FE43)) return
        _state.update { it.copy(handshakeDetail = "Sondeo telemetría obstáculo…") }
        val steps = listOf(
            "Re-sesión obstáculo" to WeWalkProtocol.buildFrame(
                WeWalkProtocol.CMD_SESSION,
                byteArrayOf(0x02),
            ),
            "Probe C1" to WeWalkProtocol.buildFrame(WeWalkProtocol.CMD_PROBE_C1),
            "Probe CA" to WeWalkProtocol.buildFrame(WeWalkProtocol.CMD_PROBE_CA),
        )
        for ((label, data) in steps) {
            val ok = writeBytes(WeWalkDevice.CHAR_TX_FE43, data)
            Log.i(TAG, "Obstacle probe TX: $label ok=$ok")
            delay(250)
        }
        _state.update { it.copy(handshakeDetail = "Listo. Acerca un obstáculo a altura de cabeza.") }
    }

    /** Pide telemetría suave ~cada 2 s solo por TX P2P (no escribir fe45: retrasa NOTIFY). */
    private fun startObstaclePoll() {
        stopObstaclePoll()
        if (!charExists(WeWalkDevice.CHAR_TX_FE43)) return
        obstaclePollJob = scope.launch {
            while (isActive && _state.value.isConnected) {
                delay(OBSTACLE_POLL_MS)
                if (!_state.value.isConnected) break
                try {
                    writeBytes(
                        WeWalkDevice.CHAR_TX_FE43,
                        WeWalkProtocol.buildFrame(WeWalkProtocol.CMD_PROBE_C1),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Obstacle poll: ${e.message}")
                }
            }
        }
        Log.i(TAG, "Obstacle poll ON cada ${OBSTACLE_POLL_MS}ms (solo fe43)")
    }

    private fun stopObstaclePoll() {
        obstaclePollJob?.cancel()
        obstaclePollJob = null
    }

    /** 0x00 = Boot Protocol Mode (Report Mode 0x01 exige 2A4D privilegiado en Samsung). */
    private suspend fun enableHidBootMode() {
        if (!charExists(WeWalkDevice.CHAR_HID_PROTOCOL_MODE)) return
        try {
            val ok = writeBytes(WeWalkDevice.CHAR_HID_PROTOCOL_MODE, byteArrayOf(0x00))
            Log.i(TAG, "HID Protocol Mode → Boot: $ok")
        } catch (e: SecurityException) {
            Log.w(TAG, "HID Protocol Mode omitido: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "HID Protocol Mode falló: ${e.message}")
        }
    }

    /**
     * En Samsung, al conectar un dispositivo con servicio HID el stack abre HID Host
     * (ble_bta_hh) y los botones van por ahí (PRIVILEGED), no por fe42.
     * BluetoothHidHost no está en el SDK público → reflexión.
     */
    private fun releaseSystemHidHost(device: BluetoothDevice) {
        val bt = adapter ?: return
        val ok = bt.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != HID_HOST_PROFILE) return
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val connected = proxy.javaClass
                            .getMethod("getConnectedDevices")
                            .invoke(proxy) as? List<BluetoothDevice>
                            ?: emptyList()
                        val isConnected = connected.any {
                            it.address.equals(device.address, ignoreCase = true)
                        }
                        Log.i(TAG, "HID_HOST connected=$isConnected for ${device.address}")
                        try {
                            val policyMethod = proxy.javaClass.getMethod(
                                "setConnectionPolicy",
                                BluetoothDevice::class.java,
                                Int::class.javaPrimitiveType,
                            )
                            val result = policyMethod.invoke(proxy, device, CONNECTION_POLICY_FORBIDDEN)
                            Log.i(TAG, "HID_HOST policy FORBIDDEN=$result")
                        } catch (e: Exception) {
                            Log.w(TAG, "HID_HOST setConnectionPolicy: ${e.message}")
                        }
                        val disconnected = proxy.javaClass
                            .getMethod("disconnect", BluetoothDevice::class.java)
                            .invoke(proxy, device)
                        Log.i(TAG, "HID_HOST disconnect=$disconnected")
                    } catch (e: SecurityException) {
                        Log.w(TAG, "HID_HOST requiere privilegio: ${e.message}")
                    } catch (e: Exception) {
                        Log.w(TAG, "HID_HOST release falló: ${e.message}")
                    } finally {
                        bt.closeProfileProxy(HID_HOST_PROFILE, proxy)
                    }
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            },
            HID_HOST_PROFILE,
        )
        if (!ok) Log.w(TAG, "No se pudo obtener proxy HID_HOST")
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
        if (useNoResponse) {
            val queued = g.writeCharacteristic(char)
            if (queued) delay(WRITE_GAP_MS)
            return queued
        }
        val result = withTimeoutOrNull(GATT_OP_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                writeContinuation = { ok -> cont.resume(ok) }
                cont.invokeOnCancellation { writeContinuation = null }
                if (!g.writeCharacteristic(char)) {
                    writeContinuation = null
                    cont.resume(false)
                }
            }
        }
        return result == true
    }

    private suspend fun subscribeAllNotify(available: Set<String>) {
        for (candidate in WeWalkDevice.NOTIFY_CANDIDATES) {
            if (!available.contains(candidate.lowercase())) continue
            try {
                enableNotifications(candidate)
            } catch (e: SecurityException) {
                Log.w(TAG, "NOTIFY omitido $candidate: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "NOTIFY falló $candidate: ${e.message}")
            }
            delay(WRITE_GAP_MS)
        }
    }

    private suspend fun enableNotifications(charUuid: String) {
        if (subscribedUuids.contains(charUuid.lowercase())) return
        val entry = charMap.entries.firstOrNull {
            it.key.endsWith("|$charUuid", ignoreCase = true)
        } ?: return
        val char = entry.value
        val g = gatt ?: return
        val ok = try {
            g.setCharacteristicNotification(char, true)
        } catch (e: SecurityException) {
            throw e
        }
        if (!ok) {
            Log.w(TAG, "setCharacteristicNotification false: $charUuid")
            return
        }
        val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val desc = char.getDescriptor(cccd) ?: run {
            Log.w(TAG, "Sin CCCD: $charUuid props=0x${char.properties.toString(16)}")
            return
        }
        // Preferir NOTIFY: en STM32 P2P (fe42) INDICATE a veces está marcado pero no envía.
        val enableValue = when {
            char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        Log.i(
            TAG,
            "NOTIFY req ${WeWalkDevice.labelForUuid(charUuid) ?: charUuid} " +
                "props=0x${char.properties.toString(16)} " +
                "cccd=${enableValue.joinToString(" ") { "%02X".format(it) }}",
        )
        desc.value = enableValue
        val wrote = withTimeoutOrNull(GATT_OP_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                descriptorContinuation = { success -> cont.resume(success) }
                cont.invokeOnCancellation { descriptorContinuation = null }
                if (!g.writeDescriptor(desc)) {
                    descriptorContinuation = null
                    cont.resume(false)
                }
            }
        } == true
        if (wrote) {
            subscribedUuids += charUuid.lowercase()
            Log.i(TAG, "NOTIFY ON ${WeWalkDevice.labelForUuid(charUuid) ?: charUuid}")
            if (handshakeCapture.isActive()) {
                handshakeCapture.info(
                    charUuid = charUuid,
                    note = "NOTIFY ON ${WeWalkDevice.labelForUuid(charUuid) ?: charUuid}",
                )
            }
        } else {
            Log.w(TAG, "NOTIFY CCCD falló $charUuid")
        }
    }

    private fun processIncoming(charUuid: String, value: ByteArray) {
        val hex = value.joinToString(" ") { "%02X".format(it) }
        // Log siempre (antes del filtro) para depurar botones.
        Log.i(TAG, "RX raw ${WeWalkDevice.labelForUuid(charUuid) ?: charUuid}: $hex")
        handshakeCapture.recordRx(
            charUuid = charUuid,
            data = value,
            label = WeWalkDevice.labelForUuid(charUuid),
        )
        when (charUuid.lowercase()) {
            WeWalkDevice.CHAR_BATTERY.lowercase() -> {
                val pct = value.firstOrNull()?.toInt()?.and(0xFF)
                if (pct != null && pct in 0..100) {
                    _state.update { it.copy(batteryPercent = pct) }
                }
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
                    if (pct in 0..100) {
                        _state.update { it.copy(batteryPercent = pct) }
                    }
                }
            }
            if (!WeWalkProtocol.isKnownSessionCmd(frame.cmd)) {
                Log.i(
                    TAG,
                    "RX OBSTACLE? PROTO cmd=0x${frame.cmd.toString(16).uppercase()} " +
                        "payload=${frame.payload.joinToString(" ") { "%02X".format(it) }} " +
                        "raw=$hex char=${WeWalkDevice.labelForUuid(charUuid) ?: charUuid}",
                )
            }
        }

        if (!WeWalkDevice.isMeaningfulPayload(hex)) return

        val label = when {
            charUuid.equals(WeWalkDevice.CHAR_HID_REPORT, ignoreCase = true) ||
                charUuid.equals(WeWalkDevice.CHAR_HID_BOOT_KB, ignoreCase = true) ->
                WeWalkDevice.describeHidReport(value)
            else -> WeWalkDevice.labelForUuid(charUuid)
        }

        Log.i(TAG, "RX ${label ?: charUuid}: $hex")
        logObstacleCandidate(charUuid, hex, value)

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

    /**
     * Etiqueta payloads que no son botón P2P / HID / batería conocida,
     * para correlacionar con detección ultrasónica en logcat.
     */
    private fun logObstacleCandidate(charUuid: String, hex: String, value: ByteArray) {
        val uuid = charUuid.lowercase()
        if (uuid == WeWalkDevice.CHAR_BATTERY.lowercase() ||
            uuid == WeWalkDevice.CHAR_BATTERY_EXT.lowercase()
        ) {
            return
        }
        if (uuid == WeWalkDevice.CHAR_HID_REPORT.lowercase() ||
            uuid == WeWalkDevice.CHAR_HID_BOOT_KB.lowercase() ||
            uuid == WeWalkDevice.CHAR_NOTIFY_13.lowercase()
        ) {
            return
        }
        if (WeWalkP2pButtons.isP2pPress(charUuid, hex)) return

        if (uuid == WeWalkDevice.CHAR_FE45.lowercase()) {
            val fe = WeWalkObstacleParser.parseFe45(hex, value)
            if (fe != null) {
                Log.i(
                    TAG,
                    "RX FE45 ${fe.kind} len=${fe.length} batt=${fe.batteryHint ?: "-"} " +
                        "dist=${fe.distanceCm ?: "-"}: $hex",
                )
                fe.distanceCm?.let { cm ->
                    // Aviso inmediato (sin pasar por SharedFlow/collectLatest).
                    caneObstacleAlertManager.onDistanceCm(cm)
                    if (WeWalkObstacleParser.isObstacleDistance(cm)) {
                        Log.i(TAG, "RX OBSTACLE fe45 dist=$cm cm")
                    }
                }
                if (fe.kind != WeWalkObstacleParser.Fe45Frame.Kind.OTHER) {
                    // Heartbeat/status: no emitir como botón.
                    if (!WeWalkDevice.isMeaningfulPayload(hex)) return
                    // Emitir eventos no-botón solo si aporta (status).
                    if (fe.kind == WeWalkObstacleParser.Fe45Frame.Kind.STATUS) {
                        _bleEvents.tryEmit(
                            CaneBleEvent(
                                charUuid = charUuid,
                                hexPayload = hex,
                                channelLabel = WeWalkDevice.labelForUuid(charUuid),
                            ),
                        )
                    }
                    return
                }
            }
        }

        val frame = WeWalkProtocol.parseFrame(value)
        if (frame != null && WeWalkProtocol.isKnownSessionCmd(frame.cmd)) return

        Log.i(
            TAG,
            "RX OBSTACLE? ${WeWalkDevice.labelForUuid(charUuid) ?: charUuid}: $hex",
        )
    }

    companion object {
        private const val TAG = "CaneBleManager"
        private const val NOTIFY_SETTLE_MS = 300L
        private const val WRITE_GAP_MS = 80L
        private const val GATT_OP_TIMEOUT_MS = 2500L
        private const val OBSTACLE_POLL_MS = 2_000L
        /** Backoff de reconexión: 2 → 5 → 15 → 30 s (tope). */
        private val BACKOFF_MS = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L)
        /** android.bluetooth.BluetoothProfile.HID_HOST (no expuesto en SDK app). */
        private const val HID_HOST_PROFILE = 4
        private const val CONNECTION_POLICY_FORBIDDEN = 0
    }
}
