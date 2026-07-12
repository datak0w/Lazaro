package io.lazaro.voice

enum class ListeningProfile {
    /** Tras hablar Lazaro: espera la palabra clave. */
    WAKE_WORD,
    /** Tras opciones o confirmación: escucha respuesta directa sin Lazaro. */
    DIRECT_RESPONSE,
}
