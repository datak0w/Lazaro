package io.lazaro.voice

import android.os.Build

object SamsungVoiceCompat {

    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    fun allowsConversationAutoListen(): Boolean = !isSamsung()

    val wakeWordListenSilenceMs: Long
        get() = if (isSamsung()) 14_000L else 12_000L

    /** Mínimo entre sesiones SR: evita pitidos cada 2-3 s en Samsung. */
    val wakeWordMinSessionGapMs: Long
        get() = if (isSamsung()) 9_000L else 6_000L

    val wakeWordRestartDelayMs: Long
        get() = wakeWordMinSessionGapMs

    /** Tras detectar comando en parcial, esperar antes de ejecutar (por si sigues hablando). */
    val partialCommandDispatchMs: Long
        get() = 750L

    val postSpeechDelayMs: Long
        get() = if (isSamsung()) 900L else 700L

    val returnToPassiveDelayMs: Long
        get() = 500L

    val pendingListenRetryMs: Long
        get() = 400L

    val commandSilenceTimeoutMs: Long
        get() = if (isSamsung()) 16_000L else 14_000L

    val directSilenceTimeoutMs: Long
        get() = commandSilenceTimeoutMs

    fun samsungResumeHint(): String? {
        return if (isSamsung()) {
            "Di Lazaro cuando quieras, o toca la pantalla."
        } else {
            null
        }
    }
}
