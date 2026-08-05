package io.lazaro.cane.ble

import io.lazaro.cane.CaneButtonAction

/**
 * Decodifica HID Boot Keyboard Input Report (UUID 2A22), 8 bytes:
 * modifiers, reserved, luego hasta 6 keycodes.
 *
 * En Samsung el Report HID (2A4D) exige BLUETOOTH_PRIVILEGED;
 * el Boot KB sí es usable tras poner Protocol Mode = Boot (0x00).
 *
 * Mapa inicial (USB HID usage); se ajusta con capturas reales.
 */
object WeWalkHidButtons {

    fun resolve(event: CaneBleEvent): CaneButtonAction? {
        if (!event.charUuid.equals(WeWalkDevice.CHAR_HID_BOOT_KB, ignoreCase = true) &&
            !event.charUuid.equals(WeWalkDevice.CHAR_HID_REPORT, ignoreCase = true)
        ) {
            return null
        }
        val bytes = event.hexPayload.split(" ")
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull(16) }
        if (bytes.isEmpty()) return null
        // Solo flanco de tecla (ignorar all-zero = soltar).
        if (bytes.all { it == 0 }) return null
        val key = if (bytes.size >= 8) {
            bytes.drop(2).firstOrNull { it != 0 }
        } else {
            bytes.firstOrNull { it != 0 }
        } ?: return null
        return actionForHidKey(key)
    }

    fun actionForHidKey(key: Int): CaneButtonAction? = when (key) {
        0x28, // Enter
        0x58, // Keypad Enter
        0x2C, // Space
        -> CaneButtonAction.LISTEN
        0x29, // Escape
        0x51, // Down arrow
        -> CaneButtonAction.CANCEL
        0x52, // Up arrow
        -> CaneButtonAction.WHERE_AM_I
        0x81, // Volume Down (si llega por boot; raro)
        -> CaneButtonAction.VOLUME_DOWN
        0x80, // Volume Up
        -> CaneButtonAction.VOLUME_UP
        else -> null
    }
}
