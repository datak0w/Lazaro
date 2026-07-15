package io.lazaro.sensor

import java.nio.charset.StandardCharsets
import java.util.UUID

object PiHubProfile {
    const val DEVICE_NAME = "LazaroHub"

    val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    val CHAR_DISTANCE: UUID = UUID.fromString("a1b2c3d4-e5f6-7891-abcd-ef1234567890")
    val CHAR_QUALITY: UUID = UUID.fromString("a1b2c3d4-e5f6-7892-abcd-ef1234567890")
    val CHAR_VISION_SUMMARY: UUID = UUID.fromString("a1b2c3d4-e5f6-7893-abcd-ef1234567890")
    val CHAR_OBJECTS_JSON: UUID = UUID.fromString("a1b2c3d4-e5f6-7894-abcd-ef1234567890")
    val CHAR_VISION_CMD: UUID = UUID.fromString("a1b2c3d4-e5f6-7895-abcd-ef1234567890")
    val CHAR_STATUS: UUID = UUID.fromString("a1b2c3d4-e5f6-7896-abcd-ef1234567890")

    val NOTIFY_CHARS = listOf(CHAR_DISTANCE, CHAR_QUALITY, CHAR_VISION_SUMMARY)

    const val CMD_SCAN_NOW: Byte = 0x01
    const val CMD_AUTO_INTERVAL: Byte = 0x02

    const val STATUS_DIST_OK = 1 shl 0
    const val STATUS_CAM_OK = 1 shl 1
    const val STATUS_WIFI_OK = 1 shl 2
    const val STATUS_API_OK = 1 shl 3
    const val STATUS_BUSY = 1 shl 4

    fun matchesDeviceName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return name.contains(DEVICE_NAME, ignoreCase = true)
    }

    fun parseDistanceCm(value: ByteArray): Int? {
        if (value.size < 2) return null
        return (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
    }

    fun parseQuality(value: ByteArray): Int? {
        return value.firstOrNull()?.toInt()?.and(0xFF)
    }

    fun parseVisionSummary(value: ByteArray): String {
        return String(value, StandardCharsets.UTF_8).trim()
    }

    fun parseStatusFlags(value: ByteArray): Int {
        return value.firstOrNull()?.toInt()?.and(0xFF) ?: 0
    }

    fun hasFlag(flags: Int, mask: Int): Boolean = flags and mask != 0
}
