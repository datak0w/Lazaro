package io.lazaro.cane

/**
 * Acciones disparadas por botones del bastón WeWALK.
 */
enum class CaneButtonAction {
    /** Centro — hablar / escuchar. */
    LISTEN,

    /** Abajo — callar y cancelar. */
    CANCEL,

    /** Arriba — ¿dónde estoy? */
    WHERE_AM_I,

    /** Volumen −. */
    VOLUME_DOWN,

    /** Volumen +. */
    VOLUME_UP,
}
