package io.lazaro.vision

enum class SceneLookTrigger {
    /** El usuario lo pide por voz o doble vol+. */
    USER_ASKED,
    /** Arranque de navegación (tras dejar hablar a Maps). */
    NAV_START,
    /** El usuario se ha parado unos segundos. */
    USER_STOPPED,
    /** Foto periódica en movimiento durante navegación. */
    NAV_PERIODIC,
}
