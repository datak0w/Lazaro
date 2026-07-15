package io.lazaro.cane.ble

/**
 * Tramas propietarias WeWALK: AA 01 CMD [payload] BB 0D 0A
 * Inferido de sesiones de ingeniería inversa (jul 2026).
 */
object WeWalkProtocol {
    const val CMD_BATTERY = 0xC5
    const val CMD_INIT = 0x01
    const val CMD_VERSION = 0x02
    const val CMD_SESSION = 0x03

    fun buildFrame(cmd: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        return byteArrayOf(0xAA.toByte(), 0x01.toByte(), cmd.toByte()) +
            payload +
            byteArrayOf(0xBB.toByte(), 0x0D.toByte(), 0x0A.toByte())
    }

    fun parseFrame(data: ByteArray): FrameInfo? {
        if (data.size < 5) return null
        val startIdx = data.indexOf(0xAA.toByte())
        if (startIdx < 0) return null
        val endIdx = data.indexOf(0xBB.toByte(), startIdx + 2)
        if (endIdx < 0 || endIdx + 2 >= data.size) return null
        val cmd = data[startIdx + 2].toInt() and 0xFF
        val payload = if (endIdx > startIdx + 3) {
            data.copyOfRange(startIdx + 3, endIdx)
        } else {
            byteArrayOf()
        }
        return FrameInfo(cmd, payload, data.copyOfRange(startIdx, endIdx + 3))
    }

    fun describePayload(cmd: Int, payload: ByteArray): String {
        return when (cmd) {
            CMD_BATTERY -> "Batería ${payload.firstOrNull()?.toInt()?.and(0xFF) ?: "?"}%"
            CMD_INIT -> "Init OK"
            CMD_VERSION -> "Versión ${payload.toHex()}"
            CMD_SESSION -> "Sesión ${payload.toHex()}"
            else -> "CMD 0x${cmd.toString(16).uppercase()} ${payload.toHex()}"
        }
    }

    data class FrameInfo(val cmd: Int, val payload: ByteArray, val raw: ByteArray)

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private fun ByteArray.indexOf(byte: Byte, from: Int = 0): Int {
        for (i in from until size) if (this[i] == byte) return i
        return -1
    }
}
