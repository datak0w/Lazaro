package io.lazaro.voice

enum class ListeningProfile {
    /** En espera: sin micrófono; activar con tap o botón del bastón. */
    STANDBY,
    /** Tras opciones o confirmación: escucha respuesta directa. */
    DIRECT_RESPONSE,
}
