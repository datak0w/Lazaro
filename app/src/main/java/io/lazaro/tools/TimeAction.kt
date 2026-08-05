package io.lazaro.tools

import io.lazaro.actions.ActionResult
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimeAction @Inject constructor(
    private val timeIntentDetector: TimeIntentDetector,
) {
    fun tryPrepare(userText: String): ActionResult? {
        if (!timeIntentDetector.detect(userText)) return null
        return ActionResult.Success(formatCurrentTime())
    }

    /**
     * Hora en 24h hablada, sin «de la mañana/tarde» (TTS es-ES lo duplicaba).
     * Ejemplo: «Son las nueve y quince.» / «Son las veintiuna y cinco.»
     */
    fun formatCurrentTime(): String {
        val now = ZonedDateTime.now(ZONE)
        val hour = now.hour
        val minute = now.minute
        val hourSpoken = speakHour(hour)
        val minuteSpoken = speakMinute(minute)
        return if (minute == 0) {
            "Son las $hourSpoken en punto."
        } else {
            "Son las $hourSpoken y $minuteSpoken."
        }
    }

    private fun speakHour(hour: Int): String = when (hour) {
        0 -> "cero"
        1 -> "una"
        21 -> "veintiuna"
        else -> CARDINALS.getOrElse(hour) { hour.toString() }
    }

    private fun speakMinute(minute: Int): String = when (minute) {
        1 -> "un"
        21 -> "veintiún"
        else -> CARDINALS.getOrElse(minute) { minute.toString() }
    }

    companion object {
        private val ZONE = ZoneId.of("Europe/Madrid")
        private val CARDINALS = mapOf(
            0 to "cero", 1 to "uno", 2 to "dos", 3 to "tres", 4 to "cuatro",
            5 to "cinco", 6 to "seis", 7 to "siete", 8 to "ocho", 9 to "nueve",
            10 to "diez", 11 to "once", 12 to "doce", 13 to "trece", 14 to "catorce",
            15 to "quince", 16 to "dieciséis", 17 to "diecisiete", 18 to "dieciocho",
            19 to "diecinueve", 20 to "veinte", 21 to "veintiuno", 22 to "veintidós",
            23 to "veintitrés", 24 to "veinticuatro", 25 to "veinticinco",
            26 to "veintiséis", 27 to "veintisiete", 28 to "veintiocho", 29 to "veintinueve",
            30 to "treinta", 31 to "treinta y uno", 32 to "treinta y dos",
            33 to "treinta y tres", 34 to "treinta y cuatro", 35 to "treinta y cinco",
            36 to "treinta y seis", 37 to "treinta y siete", 38 to "treinta y ocho",
            39 to "treinta y nueve", 40 to "cuarenta", 41 to "cuarenta y uno",
            42 to "cuarenta y dos", 43 to "cuarenta y tres", 44 to "cuarenta y cuatro",
            45 to "cuarenta y cinco", 46 to "cuarenta y seis", 47 to "cuarenta y siete",
            48 to "cuarenta y ocho", 49 to "cuarenta y nueve", 50 to "cincuenta",
            51 to "cincuenta y uno", 52 to "cincuenta y dos", 53 to "cincuenta y tres",
            54 to "cincuenta y cuatro", 55 to "cincuenta y cinco", 56 to "cincuenta y seis",
            57 to "cincuenta y siete", 58 to "cincuenta y ocho", 59 to "cincuenta y nueve",
        )
    }
}
