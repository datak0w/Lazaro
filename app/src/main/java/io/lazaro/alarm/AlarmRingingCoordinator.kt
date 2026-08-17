package io.lazaro.alarm

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Estado de alarma sonando (para «para / apaga» sin decir «alarma»). */
object AlarmRingingCoordinator {
    private val ringing = AtomicBoolean(false)
    private val alarmId = AtomicLong(-1L)

    fun markRinging(id: Long) {
        alarmId.set(id)
        ringing.set(true)
    }

    fun clear() {
        ringing.set(false)
        alarmId.set(-1L)
    }

    fun isRinging(): Boolean = ringing.get()

    fun currentAlarmId(): Long? = alarmId.get().takeIf { it > 0 && ringing.get() }
}
