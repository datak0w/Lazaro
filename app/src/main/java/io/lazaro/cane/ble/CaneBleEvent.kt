package io.lazaro.cane.ble

data class CaneBleEvent(
    val charUuid: String,
    val hexPayload: String,
    val channelLabel: String?,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun fingerprint(): String = "$charUuid|$hexPayload"
}
