package io.lazaro.cane.ble

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CaneHandshakeCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Direction { TX, RX, INFO }

    data class Entry(
        val timestampMs: Long,
        val direction: Direction,
        val charUuid: String,
        val label: String?,
        val hex: String,
        val note: String? = null,
    ) {
        fun formatLine(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
            val channel = label ?: charUuid.takeLast(8)
            val dir = when (direction) {
                Direction.TX -> "TX"
                Direction.RX -> "RX"
                Direction.INFO -> "INFO"
            }
            val suffix = note?.let { " // $it" }.orEmpty()
            return "$time [$dir] $channel ($charUuid): $hex$suffix"
        }
    }

    private val buffer = CopyOnWriteArrayList<Entry>()
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    fun start(deviceName: String?, deviceAddress: String?) {
        buffer.clear()
        _isCapturing.value = true
        _entryCount.value = 0
        info(
            charUuid = "session",
            note = "CAPTURA INICIADA — dispositivo=${deviceName ?: "?"} mac=${deviceAddress ?: "?"}",
        )
        info(
            charUuid = "hint",
            note = "Pulsa botones del bastón. Luego detén y exporta para analizar el handshake.",
        )
    }

    fun stop() {
        if (!_isCapturing.value) return
        info(charUuid = "session", note = "CAPTURA DETENIDA — ${buffer.size} líneas")
        _isCapturing.value = false
    }

    fun isActive(): Boolean = _isCapturing.value

    fun recordTx(charUuid: String, data: ByteArray, label: String? = null, note: String? = null) {
        record(Direction.TX, charUuid, data, label, note)
    }

    fun recordRx(charUuid: String, data: ByteArray, label: String? = null, note: String? = null) {
        record(Direction.RX, charUuid, data, label, note)
    }

    fun info(charUuid: String = "info", note: String) {
        if (!_isCapturing.value) return
        val entry = Entry(
            timestampMs = System.currentTimeMillis(),
            direction = Direction.INFO,
            charUuid = charUuid,
            label = null,
            hex = "—",
            note = note,
        )
        buffer.add(entry)
        _entryCount.value = buffer.size
    }

    private fun record(
        direction: Direction,
        charUuid: String,
        data: ByteArray,
        label: String?,
        note: String?,
    ) {
        if (!_isCapturing.value) return
        val entry = Entry(
            timestampMs = System.currentTimeMillis(),
            direction = direction,
            charUuid = charUuid,
            label = label,
            hex = if (data.isEmpty()) "—" else data.joinToString(" ") { "%02X".format(it) },
            note = note,
        )
        buffer.add(entry)
        _entryCount.value = buffer.size
        if (direction == Direction.RX) {
            WeWalkProtocol.parseFrame(data)?.let { frame ->
                info(
                    charUuid = charUuid,
                    note = "PROTO ${WeWalkProtocol.describePayload(frame.cmd, frame.payload)}",
                )
            }
        }
    }

    suspend fun export(
        deviceName: String?,
        deviceAddress: String?,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "cane_capture").also { it.mkdirs() }
        val file = File(dir, "handshake_${System.currentTimeMillis()}.log")
        file.bufferedWriter().use { writer ->
            writer.appendLine("# Lazaro — captura handshake WeWALK")
            writer.appendLine("# Modelo: ${WeWalkDevice.MODEL}")
            writer.appendLine("# Dispositivo: ${deviceName ?: "?"}")
            writer.appendLine("# MAC: ${deviceAddress ?: "?"}")
            writer.appendLine("# Referencia: https://github.com/wewalkio (STBlueMS / BlueST STM32)")
            writer.appendLine("# Formato tramas propietarias: AA 01 CMD [payload] BB 0D 0A")
            writer.appendLine()
            buffer.forEach { writer.appendLine(it.formatLine()) }
        }
        file
    }

    fun peekRecent(limit: Int = 5): List<Entry> = buffer.takeLast(limit)
}
