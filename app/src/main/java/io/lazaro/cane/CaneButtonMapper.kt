package io.lazaro.cane

import android.util.Log
import io.lazaro.cane.ble.CaneBleEvent
import io.lazaro.cane.ble.WeWalkDevice
import io.lazaro.cane.ble.WeWalkHidButtons
import io.lazaro.cane.ble.WeWalkP2pButtons
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CaneButtonMapper @Inject constructor(
    private val triggerBridge: CaneTriggerBridge,
) {
    private var lastFingerprint = ""
    private var lastTime = 0L

    fun onBleEvent(event: CaneBleEvent, config: CaneConfig) {
        val hidAction = WeWalkHidButtons.resolve(event)
        if (hidAction != null) {
            if (!debounce(event.fingerprint())) return
            Log.i(TAG, "Botón HID → $hidAction (${event.hexPayload})")
            triggerBridge.emit(hidAction)
            return
        }
        // HID con tecla desconocida: dejar rastro para mapear.
        if (event.charUuid.equals(WeWalkDevice.CHAR_HID_BOOT_KB, ignoreCase = true) &&
            WeWalkDevice.isMeaningfulPayload(event.hexPayload)
        ) {
            if (!debounce("hid:${event.hexPayload}")) return
            Log.i(TAG, "Botón HID raw ${event.hexPayload}")
            return
        }

        val p2pAction = WeWalkP2pButtons.resolve(event)
        if (p2pAction != null) {
            if (!debounce(event.fingerprint())) return
            Log.i(TAG, "Botón P2P → $p2pAction (${event.hexPayload})")
            triggerBridge.emit(p2pAction)
            return
        }

        // fe42 con formato de botón pero ID/EDGE desconocido: dejar rastro claro.
        if (event.charUuid.equals(WeWalkDevice.CHAR_RX_FE42, ignoreCase = true) &&
            WeWalkP2pButtons.isP2pPress(event.charUuid, event.hexPayload)
        ) {
            if (!debounce("raw:${event.hexPayload}")) return
            val edge = WeWalkP2pButtons.parseEdge(event.hexPayload)
            Log.i(
                TAG,
                "Botón P2P raw ${event.hexPayload} (id=${edge?.first} edge=${edge?.second})",
            )
            return
        }

        if (!isLearnCandidate(event)) return

        val mapped = config.primaryButtonHex != null && config.primaryButtonCharUuid != null
        if (mapped) {
            if (!event.charUuid.equals(config.primaryButtonCharUuid, ignoreCase = true)) return
            if (!event.hexPayload.equals(config.primaryButtonHex, ignoreCase = true)) return
        }

        if (!debounce(event.fingerprint())) return

        Log.i(TAG, "Botón bastón → escuchar (${event.channelLabel}: ${event.hexPayload})")
        triggerBridge.emit(CaneButtonAction.LISTEN)
    }

    fun isLearnCandidate(event: CaneBleEvent): Boolean =
        WeWalkDevice.isButtonCandidate(event.charUuid, event.hexPayload)

    private fun debounce(fp: String): Boolean {
        val now = System.currentTimeMillis()
        if (fp == lastFingerprint && now - lastTime < DEBOUNCE_MS) return false
        lastFingerprint = fp
        lastTime = now
        return true
    }

    companion object {
        private const val TAG = "CaneButtonMapper"
        private const val DEBOUNCE_MS = 500L
    }
}
