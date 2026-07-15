package io.lazaro.cane

enum class CaneHandshakeState {
    /** Aún no conectado o esperando inicio. */
    PENDING,
    /** Enviando tramas de inicialización al bastón. */
    IN_PROGRESS,
    /** Secuencia completada (no garantiza que el bastón acepte la sesión). */
    READY,
    /** Falló al menos un paso crítico. */
    FAILED,
}

data class ScannedCaneDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

data class CaneConnectionState(
    val isBluetoothEnabled: Boolean = false,
    val isScanning: Boolean = false,
    val isConnected: Boolean = false,
    val connectionLabel: String = "Desconectado",
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val batteryPercent: Int? = null,
    val lastEventHex: String? = null,
    val lastEventLabel: String? = null,
    val rssi: Int? = null,
    val handshakeState: CaneHandshakeState = CaneHandshakeState.PENDING,
    val handshakeDetail: String? = null,
)
