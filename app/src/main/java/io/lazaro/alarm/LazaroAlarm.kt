package io.lazaro.alarm

data class LazaroAlarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String = "Alarma",
    val enabled: Boolean = true,
    /** Epoch ms del próximo disparo (one-shot). */
    val triggerAtEpochMs: Long,
)
