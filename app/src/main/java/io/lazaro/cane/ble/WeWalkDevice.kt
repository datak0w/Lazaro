package io.lazaro.cane.ble

object WeWalkDevice {
    const val DEVICE_NAME = "WeWALK"
    const val MODEL = "CN-20"
    const val OFFICIAL_APP_PACKAGE = "tr.org.yga.wewalk.android"

    const val CHAR_TX_FE43 = "0000fe43-8e22-4541-9d4c-21edae82ed19"
    const val CHAR_HID_REPORT = "00002a4d-0000-1000-8000-00805f9b34fb"
    const val CHAR_HID_BOOT_KB = "00002a22-0000-1000-8000-00805f9b34fb"
    const val CHAR_HID_PROTOCOL_MODE = "00002a4e-0000-1000-8000-00805f9b34fb"
    const val CHAR_HID_REPORT_MAP = "00002a4b-0000-1000-8000-00805f9b34fb"
    const val CHAR_RX_FE42 = "0000fe42-8e22-4541-9d4c-21edae82ed19"
    const val CHAR_UART_01 = "00000001-0002-11e1-ac36-0002a5d5c51b"
    const val CHAR_UART_02 = "00000002-0002-11e1-ac36-0002a5d5c51b"
    const val CHAR_UART_25 = "00000025-0002-11e1-ac36-0002a5d5c51b"
    const val CHAR_CUSTOM_042F = "0000042f-0000-1000-8000-00805f9b34fb"
    const val CHAR_FE45 = "0000fe45-8e22-4541-9d4c-21edae82ed19"
    const val CHAR_NOTIFY_13 = "00000013-0003-11e1-ac36-0002a5d5c51b"
    const val CHAR_BATTERY = "00002a19-0000-1000-8000-00805f9b34fb"
    const val CHAR_BATTERY_EXT = "00002beb-0000-1000-8000-00805f9b34fb"

    val NOTIFY_CANDIDATES = listOf(
        CHAR_HID_REPORT,
        CHAR_HID_BOOT_KB,
        CHAR_RX_FE42,
        CHAR_UART_01,
        CHAR_UART_02,
        CHAR_UART_25,
        CHAR_CUSTOM_042F,
        CHAR_FE45,
        CHAR_NOTIFY_13,
        CHAR_BATTERY,
    )

    fun matchesDeviceName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return name.contains("wewalk", ignoreCase = true)
    }

    fun labelForUuid(uuid: String): String? = when (uuid.lowercase()) {
        CHAR_TX_FE43.lowercase() -> "TX P2P"
        CHAR_RX_FE42.lowercase() -> "RX P2P"
        CHAR_UART_01.lowercase() -> "UART principal"
        CHAR_UART_02.lowercase() -> "UART secundario"
        CHAR_UART_25.lowercase() -> "UART RX alt"
        CHAR_CUSTOM_042F.lowercase() -> "Custom 042F"
        CHAR_FE45.lowercase() -> "Canal fe45"
        CHAR_NOTIFY_13.lowercase() -> "Notify 13"
        CHAR_HID_REPORT.lowercase() -> "HID Report"
        CHAR_HID_BOOT_KB.lowercase() -> "HID Boot KB"
        CHAR_HID_PROTOCOL_MODE.lowercase() -> "HID Protocol"
        CHAR_HID_REPORT_MAP.lowercase() -> "HID Map"
        CHAR_BATTERY.lowercase() -> "Batería"
        CHAR_BATTERY_EXT.lowercase() -> "Batería ext"
        else -> null
    }

    fun isButtonCandidate(charUuid: String, hexPayload: String): Boolean {
        if (!isMeaningfulPayload(hexPayload)) return false
        val uuid = charUuid.lowercase()
        if (uuid == CHAR_HID_REPORT.lowercase() || uuid == CHAR_HID_BOOT_KB.lowercase()) return true
        if (uuid == CHAR_BATTERY.lowercase() || uuid == CHAR_BATTERY_EXT.lowercase()) return false
        if (uuid == CHAR_NOTIFY_13.lowercase()) return false
        if (uuid == CHAR_RX_FE42.lowercase() && hexPayload.matches(Regex("^[0-9A-F]{2} [0-9A-F]{2}$"))) return false
        return true
    }

    fun isMeaningfulPayload(hexPayload: String): Boolean {
        if (hexPayload.isBlank()) return false
        val bytes = hexPayload.split(" ").filter { it.isNotBlank() }
        if (bytes.isEmpty()) return false
        return bytes.any { it != "00" }
    }

    fun describeHidReport(data: ByteArray): String {
        if (data.isEmpty()) return "vacío"
        if (data.size >= 8) {
            val keys = data.drop(2).take(6).map { it.toInt() and 0xFF }.filter { it != 0 }
            return "keys=${keys.joinToString { "0x%02X".format(it) }}"
        }
        return data.joinToString(" ") { "%02X".format(it) }
    }
}
