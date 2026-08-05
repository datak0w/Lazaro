package io.lazaro.cane.ble

import io.lazaro.cane.CaneButtonAction

/**
 * Decodifica NOTIFY P2P fe42: payloads de 2 bytes `ID EDGE`.
 *
 * WeWALK 2 (SM-A346B, ago 2026) — mapa capturado en secuencia:
 * - 02 = centro (Select) → LISTEN
 * - 01 = arriba → WHERE_AM_I
 * - 00 = abajo → CANCEL
 * - 05 = volumen + → VOLUME_UP
 * - 04 = volumen − → VOLUME_DOWN
 */
object WeWalkP2pButtons {

    private val EDGE = Regex("^([0-9A-F]{2}) (01|02)$", RegexOption.IGNORE_CASE)

    fun resolve(event: CaneBleEvent): CaneButtonAction? {
        if (!event.charUuid.equals(WeWalkDevice.CHAR_RX_FE42, ignoreCase = true)) return null
        return resolvePayload(event.hexPayload)
    }

    fun resolvePayload(hexPayload: String): CaneButtonAction? {
        val match = EDGE.matchEntire(hexPayload.trim()) ?: return null
        val id = match.groupValues[1].uppercase()
        // Firmware WeWALK 2: flancos 01 y 02 cuentan como pulsación.
        return actionForId(id)
    }

    /** Cualquier `ID 01|02` en fe42 cuenta como candidato de botón (para logs / aprendizaje). */
    fun isP2pPress(charUuid: String, hexPayload: String): Boolean {
        if (!charUuid.equals(WeWalkDevice.CHAR_RX_FE42, ignoreCase = true)) return false
        return EDGE.matches(hexPayload.trim())
    }

    fun actionForId(id: String): CaneButtonAction? = when (id.uppercase()) {
        "02" -> CaneButtonAction.LISTEN
        "01" -> CaneButtonAction.WHERE_AM_I
        "00" -> CaneButtonAction.CANCEL
        "05" -> CaneButtonAction.VOLUME_UP
        "04" -> CaneButtonAction.VOLUME_DOWN
        else -> null
    }

    fun parseEdge(hexPayload: String): Pair<String, String>? {
        val match = EDGE.matchEntire(hexPayload.trim()) ?: return null
        return match.groupValues[1].uppercase() to match.groupValues[2]
    }
}
