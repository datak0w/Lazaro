package io.lazaro.voice

enum class ListeningProfile {
    /** En espera: Vosk wake word, sin Google STT (sin pitidos de conexión). */
    STANDBY,
    /** Tras wake / confirmación: Google STT para el comando. */
    DIRECT_RESPONSE,
}
