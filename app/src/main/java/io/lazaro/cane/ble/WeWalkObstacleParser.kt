package io.lazaro.cane.ble

/**
 * Decodifica telemetría WeWALK relacionada con obstáculos / status.
 *
 * Captura 2026-08-05 #2 (Samsung + WeWALK 2):
 * - fe45 STATUS `6D 00 A5 5A …` (~10 s): u16 LE en offset 10 ≈ distancia cm
 *   (idle/máx ~241; baja al acercar objeto a ~192 en la prueba).
 * - fe45 corto = heartbeat.
 * - Notify 13 = IMU (no usar).
 * - UART / 042F: sin RX en sesiones.
 */
object WeWalkObstacleParser {

    data class ObstacleReading(
        val distanceCm: Int?,
        val rawCmd: Int?,
        val charUuid: String,
        val hexPayload: String,
        val zone: Zone = Zone.HEAD_LEVEL,
    )

    enum class Zone {
        HEAD_LEVEL,
        UNKNOWN,
    }

    data class Fe45Frame(
        val length: Int,
        val kind: Kind,
        val batteryHint: Int?,
        /** Distancia estimada (cm) en status largo; null si no aplica. */
        val distanceCm: Int?,
        val hexPayload: String,
    ) {
        enum class Kind { HEARTBEAT, STATUS, OTHER }
    }

    fun parse(charUuid: String, hexPayload: String, raw: ByteArray? = null): ObstacleReading? {
        val bytes = raw ?: hexToBytes(hexPayload) ?: return null
        if (!WeWalkDevice.isMeaningfulPayload(hexPayload)) return null
        if (WeWalkP2pButtons.isP2pPress(charUuid, hexPayload)) return null
        val uuid = charUuid.lowercase()
        if (uuid == WeWalkDevice.CHAR_BATTERY.lowercase() ||
            uuid == WeWalkDevice.CHAR_BATTERY_EXT.lowercase() ||
            uuid == WeWalkDevice.CHAR_HID_BOOT_KB.lowercase() ||
            uuid == WeWalkDevice.CHAR_HID_REPORT.lowercase() ||
            uuid == WeWalkDevice.CHAR_NOTIFY_13.lowercase()
        ) {
            return null
        }

        if (uuid == WeWalkDevice.CHAR_FE45.lowercase()) {
            val fe = parseFe45(hexPayload, bytes) ?: return null
            val cm = fe.distanceCm ?: return null
            if (!isObstacleDistance(cm)) return null
            return ObstacleReading(
                distanceCm = cm,
                rawCmd = null,
                charUuid = charUuid,
                hexPayload = hexPayload,
                zone = Zone.HEAD_LEVEL,
            )
        }

        val frame = WeWalkProtocol.parseFrame(bytes)
        if (frame != null && WeWalkProtocol.isKnownSessionCmd(frame.cmd)) return null
        return null
    }

    fun parseFe45(hexPayload: String, raw: ByteArray? = null): Fe45Frame? {
        val bytes = raw ?: hexToBytes(hexPayload) ?: return null
        if (bytes.size < 4) return null
        val len = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        if (bytes[2] != 0xA5.toByte() || bytes[3] != 0x5A.toByte()) {
            return Fe45Frame(len, Fe45Frame.Kind.OTHER, null, null, hexPayload)
        }
        val kind = when {
            bytes.size == 10 && len == 8 -> Fe45Frame.Kind.HEARTBEAT
            bytes.size >= 20 && len >= 0x20 -> Fe45Frame.Kind.STATUS
            else -> Fe45Frame.Kind.OTHER
        }
        var battery: Int? = null
        if (kind == Fe45Frame.Kind.STATUS && bytes.size >= 18) {
            val b0 = bytes[14].toInt() and 0xFF
            val b1 = bytes[16].toInt() and 0xFF
            if (b0 in 1..100 && b0 == b1) battery = b0
        }
        val distanceCm = if (kind == Fe45Frame.Kind.STATUS && bytes.size >= 12) {
            val cm = (bytes[10].toInt() and 0xFF) or ((bytes[11].toInt() and 0xFF) shl 8)
            cm.takeIf { it in DISTANCE_MIN_CM..DISTANCE_MAX_CM }
        } else {
            null
        }
        return Fe45Frame(len, kind, battery, distanceCm, hexPayload)
    }

    /** True si el valor parece obstáculo (no el techo ~241 de “libre”). */
    fun isObstacleDistance(cm: Int): Boolean =
        cm in DISTANCE_MIN_CM until CLEAR_DISTANCE_CM

    /**
     * Frase hablada: “Obstáculo enfrente” + cubo de distancia
     * (medio / un / metro y medio / dos metros). Sin centímetros exactos.
     */
    fun announceFrontalPhrase(distanceCm: Int?): String {
        if (distanceCm == null) return "Obstáculo enfrente."
        val bucket = when {
            distanceCm <= 75 -> "a medio metro"
            distanceCm <= 125 -> "a un metro"
            distanceCm <= 175 -> "a un metro y medio"
            else -> "a dos metros"
        }
        return "Obstáculo enfrente, $bucket."
    }

    /** Cubo estable para cooldown (evitar spam al oscilar 1 cm). */
    fun distanceBucket(cm: Int): Int = when {
        cm <= 75 -> 50
        cm <= 125 -> 100
        cm <= 175 -> 150
        else -> 200
    }

    fun isCaptureCandidate(charUuid: String, hexPayload: String): Boolean {
        if (!WeWalkDevice.isMeaningfulPayload(hexPayload)) return false
        if (WeWalkP2pButtons.isP2pPress(charUuid, hexPayload)) return false
        val uuid = charUuid.lowercase()
        if (uuid == WeWalkDevice.CHAR_BATTERY.lowercase() ||
            uuid == WeWalkDevice.CHAR_BATTERY_EXT.lowercase() ||
            uuid == WeWalkDevice.CHAR_HID_BOOT_KB.lowercase() ||
            uuid == WeWalkDevice.CHAR_HID_REPORT.lowercase() ||
            uuid == WeWalkDevice.CHAR_NOTIFY_13.lowercase()
        ) {
            return false
        }
        if (uuid == WeWalkDevice.CHAR_FE45.lowercase()) {
            val fe = parseFe45(hexPayload) ?: return true
            if (fe.kind == Fe45Frame.Kind.STATUS && fe.distanceCm != null &&
                isObstacleDistance(fe.distanceCm)
            ) {
                return true
            }
            return fe.kind == Fe45Frame.Kind.OTHER
        }
        val bytes = hexToBytes(hexPayload) ?: return true
        val frame = WeWalkProtocol.parseFrame(bytes)
        if (frame != null && WeWalkProtocol.isKnownSessionCmd(frame.cmd)) return false
        return true
    }

    private fun hexToBytes(hexPayload: String): ByteArray? {
        val parts = hexPayload.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return try {
            parts.map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    const val DISTANCE_MIN_CM = 5
    const val DISTANCE_MAX_CM = 300
    /** Lecturas ≥ esto se tratan como “libre / sin eco cercano”. */
    const val CLEAR_DISTANCE_CM = 235
}
