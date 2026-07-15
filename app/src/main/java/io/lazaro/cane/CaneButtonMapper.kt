package io.lazaro.cane

import android.util.Log
import io.lazaro.cane.ble.CaneBleEvent
import io.lazaro.cane.ble.WeWalkDevice
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CaneButtonMapper @Inject constructor(
    private val triggerBridge: CaneTriggerBridge,
) {
    private var lastFingerprint = ""
    private var lastTime = 0L

    fun onBleEvent(event: CaneBleEvent, config: CaneConfig) {
        if (!isLearnCandidate(event)) return

        val mapped = config.primaryButtonHex != null && config.primaryButtonCharUuid != null
        if (mapped) {
            if (!event.charUuid.equals(config.primaryButtonCharUuid, ignoreCase = true)) return
            if (!event.hexPayload.equals(config.primaryButtonHex, ignoreCase = true)) return
        }

        val fp = event.fingerprint()
        val now = System.currentTimeMillis()
        if (fp == lastFingerprint && now - lastTime < 500) return
        lastFingerprint = fp
        lastTime = now

        Log.i(TAG, "Botón bastón → escuchar (${event.channelLabel}: ${event.hexPayload})")
        triggerBridge.emitCaneButtonPress()
    }

    fun isLearnCandidate(event: CaneBleEvent): Boolean =
        WeWalkDevice.isButtonCandidate(event.charUuid, event.hexPayload)

    companion object {
        private const val TAG = "CaneButtonMapper"
    }
}
