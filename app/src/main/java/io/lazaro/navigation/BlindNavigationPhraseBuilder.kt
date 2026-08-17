package io.lazaro.navigation

import io.lazaro.pathguide.MapsInstructionType
import io.lazaro.pathguide.RoadSide
import io.lazaro.pathguide.SidewalkAlignment
import io.lazaro.pathguide.StreetLayoutState

/**
 * Frases cortas y accionables para navegación a ciegas.
 * Maps + IMU + cámara se fusionan en un tip claro, sin jerga de navegador.
 */
object BlindNavigationPhraseBuilder {

    enum class Action {
        FORWARD,
        TURN_LEFT,
        TURN_RIGHT,
        U_TURN,
        CROSS,
        ARRIVE,
        OTHER,
    }

    /** Acción principal a partir de la instrucción de Maps. */
    fun actionFromMaps(
        instruction: String,
        type: MapsInstructionType = MapsNavigationParser.classifyInstruction(instruction),
        side: TurnSide? = MapsNavigationParser.turnSide(instruction),
    ): Action {
        return when {
            type == MapsInstructionType.ARRIVE || MapsNavigationParser.isArrival(instruction) ->
                Action.ARRIVE
            type == MapsInstructionType.CROSS_STREET -> Action.CROSS
            side == TurnSide.U_TURN || isUTurn(instruction) -> Action.U_TURN
            side == TurnSide.LEFT -> Action.TURN_LEFT
            side == TurnSide.RIGHT -> Action.TURN_RIGHT
            type == MapsInstructionType.STRAIGHT -> Action.FORWARD
            type == MapsInstructionType.TURN || type == MapsInstructionType.ROUNDABOUT ->
                when (side) {
                    TurnSide.LEFT -> Action.TURN_LEFT
                    TurnSide.RIGHT -> Action.TURN_RIGHT
                    TurnSide.U_TURN -> Action.U_TURN
                    null -> Action.OTHER
                }
            looksStraight(instruction) -> Action.FORWARD
            else -> Action.OTHER
        }
    }

    /** Tip principal que deben oír siempre (una frase, acción clara). */
    fun primaryTip(action: Action): String = when (action) {
        Action.FORWARD -> "Continúa hacia adelante."
        Action.TURN_LEFT -> "Gira a la izquierda."
        Action.TURN_RIGHT -> "Gira a la derecha."
        Action.U_TURN -> "Da la vuelta."
        Action.CROSS -> "Ahora tienes que cruzar. Cruza con cuidado."
        Action.ARRIVE -> "Has llegado a tu destino."
        Action.OTHER -> "Sigue la indicación."
    }

    /** Refuerzo periódico en tramos largos rectos (variar para no aburrir). */
    fun announceContinueStraight(
        metersToNextManeuver: Int?,
        streetName: String? = null,
        variation: Int = 0,
    ): String {
        val street = streetName?.trim()?.takeIf { it.length >= 2 }
        val ahead = metersToNextManeuver?.takeIf { it in 20..400 }
        val base = when ((variation % 4 + 4) % 4) {
            1 -> "Sigue recto."
            2 -> "Mantén el rumbo. Continúa hacia adelante."
            3 -> "Todo bien. Sigue caminando hacia adelante."
            else -> "Continúa hacia adelante."
        }
        return buildString {
            append(base)
            if (street != null) append(" Por $street.")
            if (ahead != null && ahead >= 40) {
                append(" El próximo cambio es en unos ${roundMeters(ahead)} metros.")
            }
        }.trim()
    }

    /** Corrección de rumbo cuando el teléfono no apunta al tramo. */
    fun announceHeadingCorrection(action: Action): String? = when (action) {
        Action.TURN_LEFT -> "Corrige: gira un poco a la izquierda y sigue."
        Action.TURN_RIGHT -> "Corrige: gira un poco a la derecha y sigue."
        Action.U_TURN -> "Vas en sentido contrario. Da la vuelta."
        else -> null
    }

    /** Cambio de calle al entrar en un tramo nuevo. */
    fun announceStreetChange(streetName: String): String {
        val street = streetName.trim()
        return "Entras en $street. Continúa hacia adelante."
    }

    /** Tras completar un giro. */
    fun announceAfterTurn(): String = "Perfecto. Ahora continúa hacia adelante."

    /** Anticipa el próximo giro OSRM a N metros (opcionalmente a calle). */
    fun announceNextManeuver(
        action: Action,
        distanceM: Int,
        streetName: String? = null,
    ): String {
        val dist = distanceM.coerceIn(5, 500)
        val verb = when (action) {
            Action.TURN_LEFT -> "gira a la izquierda"
            Action.TURN_RIGHT -> "gira a la derecha"
            Action.U_TURN -> "da la vuelta"
            Action.CROSS -> "tienes que cruzar"
            Action.ARRIVE -> "llegas a tu destino"
            Action.FORWARD -> "sigue recto"
            Action.OTHER -> "prepárate para el siguiente paso"
        }
        val street = streetName?.trim()?.takeIf { it.length >= 2 }
        val toward = if (street != null && action != Action.ARRIVE && action != Action.CROSS) {
            " hacia $street"
        } else {
            ""
        }
        return when {
            dist <= 12 -> when (action) {
                Action.CROSS -> "Ahora, cruza la calle$toward."
                else -> "Ahora, $verb$toward."
            }
            dist <= 25 -> "En unos $dist metros, $verb$toward."
            dist <= 60 -> "Prepárate: en unos $dist metros, $verb$toward."
            else -> "Más adelante, en unos ${roundMeters(dist)} metros, $verb$toward."
        }
    }

    fun announceCrossingAhead(
        distanceM: Int,
        side: CrossingSide = CrossingSide.AHEAD,
    ): String {
        val d = distanceM.coerceIn(5, 120)
        val where = when (side) {
            CrossingSide.LEFT -> "a tu izquierda"
            CrossingSide.RIGHT -> "a tu derecha"
            CrossingSide.AHEAD -> "delante"
        }
        return when {
            d <= 15 && side != CrossingSide.AHEAD ->
                "Paso de cebra $where. Ahora tienes que cruzar."
            d <= 15 ->
                "Paso de cebra delante. Ahora tienes que cruzar."
            d <= 35 ->
                "Paso de cebra $where a unos $d metros. Prepárate para cruzar."
            else ->
                "Hay un cruce peatonal $where a unos $d metros."
        }
    }

    enum class CrossingSide {
        LEFT,
        RIGHT,
        AHEAD,
    }

    private fun roundMeters(meters: Int): Int {
        return when {
            meters <= 30 -> meters
            meters <= 100 -> (meters / 5) * 5
            else -> (meters / 10) * 10
        }
    }

    /** Primera indicación propia al arrancar (Maps aún no ha hablado). */
    fun announceInitialHeading(
        action: Action,
        distanceM: Int,
        label: String,
    ): String {
        val name = label.trim().ifBlank { "tu destino" }
        val parts = mutableListOf("Para llegar a $name: ${primaryTip(action).trimEnd('.')}.")
        distancePhrase(distanceM)?.let { parts.add(it) }
        return parts.joinToString(" ").trim()
    }

    /**
     * Resumen de arranque con plan OSRM: destino, ETA, metros y primera calle.
     */
    fun announceRouteStart(
        label: String,
        totalDistanceM: Int,
        etaMinutes: Int,
        firstStreet: String?,
        firstAction: Action,
    ): String {
        val name = label.trim().ifBlank { "tu destino" }
        val meters = totalDistanceM.coerceAtLeast(10)
        val roundedM = if (meters >= 100) (meters / 10) * 10 else meters
        val minutes = etaMinutes.coerceAtLeast(1)
        val parts = mutableListOf(
            "Hacia $name. Unos $minutes minutos, $roundedM metros.",
        )
        val street = firstStreet?.trim()?.takeIf { it.length >= 2 }
        if (street != null) {
            parts.add("Primero por $street.")
        } else {
            parts.add(primaryTip(firstAction).trimEnd('.') + ".")
        }
        return parts.joinToString(" ").trim()
    }

    /** Tip de seguimiento propio (sin dependencia de Maps). */
    fun announceOwnGuidance(action: Action, distanceM: Int): String {
        val parts = mutableListOf(primaryTip(action))
        distancePhrase(distanceM)?.let { parts.add(it) }
        return parts.joinToString(" ").trim()
    }

    /** Hito de distancia restante al destino (con ETA opcional). */
    fun announceDistanceMilestone(
        metersLeft: Int,
        label: String = "tu destino",
        etaMinutes: Int? = null,
    ): String {
        val name = label.trim().ifBlank { "tu destino" }
        val rounded = when {
            metersLeft <= 30 -> metersLeft.coerceAtLeast(5)
            metersLeft <= 100 -> (metersLeft / 5) * 5
            else -> (metersLeft / 10) * 10
        }
        val eta = etaMinutes?.takeIf { it >= 1 }?.let { " Unos $it minutos." }.orEmpty()
        return "Quedan unos $rounded metros hasta $name.$eta"
    }

    fun announceOffRouteRecalc(): String =
        "Te saliste de la ruta. Recalculando."

    /** Sin brújula/GPS bearing: pedir calibración al caminar. */
    fun announceCalibrateHeading(label: String): String {
        val name = label.trim().ifBlank { "tu destino" }
        return "Para llegar a $name: camina unos pasos para orientar el teléfono."
    }

    private fun distancePhrase(meters: Int): String? {
        return when {
            meters in 5..15 -> "En unos pocos pasos."
            meters in 16..40 -> "En unos $meters metros."
            meters in 41..400 -> "Está a unos $meters metros."
            meters > 400 -> "Está a unos ${meters / 10 * 10} metros."
            else -> null
        }
    }

    /**
     * Anuncio completo al llegar un aviso de Maps:
     * 1) acción clara Lazaro
     * 2) detalle útil de Maps (calle / metros) si aporta
     * 3) cola corta de acera en giros/cruces si la visión aporta lado seguro
     */
    fun announceFromMaps(
        instruction: String,
        type: MapsInstructionType = MapsNavigationParser.classifyInstruction(instruction),
        streetLayout: StreetLayoutState? = null,
    ): String {
        val side = MapsNavigationParser.turnSide(instruction)
        val action = actionFromMaps(instruction, type, side)
        val parts = mutableListOf(primaryTip(action))

        distanceHint(instruction)?.let { parts.add(it) }
        streetNameHint(instruction, action)?.let { parts.add(it) }
        if (action == Action.TURN_LEFT || action == Action.TURN_RIGHT ||
            action == Action.CROSS || action == Action.U_TURN
        ) {
            sidewalkHint(streetLayout)?.let { parts.add(it) }
        }

        return parts.joinToString(" ").trim()
    }

    private fun sidewalkHint(streetLayout: StreetLayoutState?): String? {
        return when (streetLayout?.safeSide) {
            RoadSide.LEFT -> "Mantén la acera a tu izquierda."
            RoadSide.RIGHT -> "Mantén la acera a tu derecha."
            else -> null
        }
    }

    /** Tip durante el giro (IMU + Maps): grados restantes → precisión. */
    fun imuTurnTip(remainingDeg: Float, turnSide: TurnSide?): String? {
        val mag = kotlin.math.abs(remainingDeg)
        if (mag < 8f) return null // el “Perfecto. Camina hacia adelante.” lo dice alineación
        val sideWord = when {
            turnSide == TurnSide.U_TURN -> if (remainingDeg < 0f) "izquierda" else "derecha"
            remainingDeg < 0f || turnSide == TurnSide.LEFT -> "izquierda"
            remainingDeg > 0f || turnSide == TurnSide.RIGHT -> "derecha"
            else -> return null
        }
        return when {
            turnSide == TurnSide.U_TURN && mag > 90f -> "Sigue girando. Da la vuelta completa."
            turnSide == TurnSide.U_TURN && mag > 40f -> "Sigue. Casi das la vuelta."
            mag > 35f -> "Gira a la $sideWord."
            mag > 18f -> "Gira un poco a la $sideWord."
            else -> "Un poco más a la $sideWord."
        }
    }

    /** Tip al caminar recto con acera detectada. */
    fun walkingStraightTip(layout: StreetLayoutState?, frontalBlocked: Boolean): String? {
        if (frontalBlocked) return null
        return "Continúa hacia adelante."
    }

    private fun distanceHint(instruction: String): String? {
        val meters = Regex("""(\d+)\s*m(?:etros?)?\b""", RegexOption.IGNORE_CASE)
            .find(instruction)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val feetOrMin = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
            .find(instruction)?.groupValues?.getOrNull(1)
        return when {
            meters != null && meters in 5..400 -> {
                val approx = when {
                    meters <= 15 -> "En unos pocos pasos."
                    meters <= 40 -> "En unos $meters metros."
                    else -> "En aproximadamente $meters metros."
                }
                approx
            }
            feetOrMin != null -> null
            else -> null
        }
    }

    private fun streetNameHint(instruction: String, action: Action): String? {
        if (action == Action.OTHER || action == Action.ARRIVE) return null
        // Evitar repetir toda la frase de Maps; solo si hay nombre de vía útil
        val toward = Regex(
            """(?:hacia|por|a)\s+(?:la\s+|el\s+|calle\s+|av(?:enida)?\.?\s+|plaza\s+)?([A-ZÁÉÍÓÚÑ][\wÁÉÍÓÚáéíóúñ\s\-.]{2,40})""",
        ).find(instruction)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', ',')
        if (toward.isNullOrBlank()) return null
        if (toward.length < 3 || toward.equals("la", true)) return null
        return "Hacia $toward."
    }

    private fun isUTurn(instruction: String): Boolean {
        val n = normalize(instruction)
        return n.contains("u-turn") ||
            n.contains("retorno") ||
            n.contains("media vuelta") ||
            n.contains("da la vuelta") ||
            n.contains("dar la vuelta") ||
            n.contains("cambiar de sentido") ||
            n.contains("cambio de sentido") ||
            n.contains("vuelta en u")
    }

    private fun looksStraight(instruction: String): Boolean {
        val n = normalize(instruction)
        return listOf(
            "sigue recto", "siga recto", "continua recto", "continúa recto",
            "camina", "go straight", "head straight", "mantente en",
            "continúa por", "continua por", "sigue por",
        ).any { n.contains(it) } &&
            !n.contains("izquierda") &&
            !n.contains("derecha") &&
            !isUTurn(instruction)
    }

    private fun normalize(text: String): String {
        val withoutAccents = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return withoutAccents.lowercase()
    }
}
