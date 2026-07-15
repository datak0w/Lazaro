package io.lazaro.sensor

data class ScannedPiHubDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

data class PiHubConnectionState(
    val isBluetoothEnabled: Boolean = false,
    val isScanning: Boolean = false,
    val isConnected: Boolean = false,
    val connectionLabel: String = "Desconectado",
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val rssi: Int? = null,
    val distanceCm: Int = 0,
    val quality: Int = 0,
    val visionSummary: String = "",
    val objectsJson: String = "",
    val statusFlags: Int = 0,
) {
    val distOk: Boolean get() = PiHubProfile.hasFlag(statusFlags, PiHubProfile.STATUS_DIST_OK)
    val camOk: Boolean get() = PiHubProfile.hasFlag(statusFlags, PiHubProfile.STATUS_CAM_OK)
    val wifiOk: Boolean get() = PiHubProfile.hasFlag(statusFlags, PiHubProfile.STATUS_WIFI_OK)
    val apiOk: Boolean get() = PiHubProfile.hasFlag(statusFlags, PiHubProfile.STATUS_API_OK)
    val busy: Boolean get() = PiHubProfile.hasFlag(statusFlags, PiHubProfile.STATUS_BUSY)
}

data class PiHubConfig(
    val savedMac: String? = null,
    val savedName: String? = null,
    val distanceAlertCm: Int = 50,
    val distanceAlertsEnabled: Boolean = true,
    val visionAutoIntervalSec: Int = 0,
    val visionTtsEnabled: Boolean = true,
)
