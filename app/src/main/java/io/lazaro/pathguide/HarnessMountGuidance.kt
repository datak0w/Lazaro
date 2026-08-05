package io.lazaro.pathguide

/**
 * Textos y frases del modo arnés / clip (Pixel a altura de ojos o pecho).
 * Sustituye gafas Meta: la profundidad viene de ARCore/LDAF del propio móvil.
 */
object HarnessMountGuidance {

    const val SHORT_CUE =
        "Coloca el teléfono en el arnés o clip, cámara trasera al frente, " +
            "a altura de pecho u ojos."

    const val FULL_CUE =
        "$SHORT_CUE " +
            "En Pixel uso profundidad ARCore. Silencio en los pitidos significa que vas centrado. " +
            "Si te desvías, aléjate del pitido."

    fun startMessage(harnessEnabled: Boolean, depthMode: DepthGuidanceMode): String {
        if (!harnessEnabled) {
            return "Te guío con pitidos por la cámara. Di Lázaro, terminar paseo, para parar."
        }
        val depthHint = when (depthMode) {
            DepthGuidanceMode.ARCORE_DEPTH ->
                "Profundidad ARCore activa."
            DepthGuidanceMode.LDAF_ONLY ->
                "Distancia frontal por autofocus activa."
            DepthGuidanceMode.MONOCULAR ->
                "Sin mapa de profundidad en este móvil; la guía es por imagen."
        }
        return "$SHORT_CUE $depthHint " +
            "Silencio significa centrado. Di Lázaro, terminar paseo, para parar."
    }
}
