package io.lazaro.cane

/**
 * Acciones disparadas por botones del bastón WeWALK.
 */
enum class CaneButtonAction {
    /** Centro — hablar / escuchar. */
    LISTEN,

    /** Abajo — callar y cancelar. */
    CANCEL,

    /**
     * Arriba — foto y descripción («dime qué ves»).
     * (Antes era «dónde estoy»; ese va a doble vol+.)
     */
    WHERE_AM_I,

    /** Volumen −. Un toque: bajar. Doble: modo dormir. */
    VOLUME_DOWN,

    /** Volumen +. Un toque: subir. Doble: ¿dónde estoy? */
    VOLUME_UP,
}
