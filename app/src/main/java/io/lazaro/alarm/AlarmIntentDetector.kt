package io.lazaro.alarm

import io.lazaro.tools.SpanishSpokenNumbers
import java.text.Normalizer
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

data class ParsedClockTime(val hour: Int, val minute: Int)

sealed class AlarmVoiceIntent {
    data class Set(val time: ParsedClockTime, val label: String = "Alarma") : AlarmVoiceIntent()
    data class Change(
        val from: ParsedClockTime?,
        val to: ParsedClockTime,
    ) : AlarmVoiceIntent()
    data class Cancel(val time: ParsedClockTime?) : AlarmVoiceIntent()
    data object StopRinging : AlarmVoiceIntent()
    data object List : AlarmVoiceIntent()
}

@Singleton
class AlarmIntentDetector @Inject constructor() {

    fun detect(userText: String): AlarmVoiceIntent? {
        val raw = normalize(userText)
        if (raw.isBlank()) return null

        val withDigits = SpanishSpokenNumbers.replaceSpokenNumbers(raw)

        if (isList(withDigits)) return AlarmVoiceIntent.List

        if (isStopRinging(withDigits)) return AlarmVoiceIntent.StopRinging

        if (isCancel(withDigits)) {
            val time = extractTime(withDigits)
            return AlarmVoiceIntent.Cancel(time)
        }

        if (isChange(withDigits)) {
            val times = extractAllTimes(withDigits)
            return when {
                times.size >= 2 -> AlarmVoiceIntent.Change(from = times[0], to = times[1])
                times.size == 1 -> AlarmVoiceIntent.Change(from = null, to = times[0])
                else -> null
            }
        }

        if (isSet(withDigits)) {
            parseRelative(withDigits)?.let { return AlarmVoiceIntent.Set(it) }
            val time = extractTime(withDigits) ?: return null
            return AlarmVoiceIntent.Set(time)
        }

        return null
    }

    fun isAlarmStopWhileRinging(userText: String): Boolean {
        if (!AlarmRingingCoordinator.isRinging()) return false
        val t = normalize(userText)
        if (t.contains("alarma") || t.contains("despertador")) return true
        return STOP_WHILE_RINGING.any { phrase ->
            t == phrase || t.startsWith("$phrase ") || t.endsWith(" $phrase") ||
                t.contains(" $phrase ")
        }
    }

    private fun isList(t: String): Boolean {
        return listOf(
            "que alarmas", "que alarma", "mis alarmas", "lista alarmas",
            "listar alarmas", "alarmas pendientes", "cuales son mis alarmas",
            "que despertadores",
        ).any { t.contains(it) }
    }

    private fun isStopRinging(t: String): Boolean {
        if (AlarmRingingCoordinator.isRinging()) {
            if (t.contains("alarma") || t.contains("despertador")) return true
            if (STOP_WHILE_RINGING.any { phrase ->
                    t == phrase || t.startsWith("$phrase ") || t.endsWith(" $phrase") ||
                        t.contains(" $phrase ")
                }
            ) {
                return true
            }
        }
        return listOf(
            "para la alarma", "apaga la alarma", "apagar la alarma",
            "silencia la alarma", "quita el sonido de la alarma",
            "para el despertador", "apaga el despertador",
            "calla la alarma", "basta la alarma",
        ).any { t.contains(it) }
    }

    private fun isCancel(t: String): Boolean {
        val hasAlarm = t.contains("alarma") || t.contains("despertador")
        if (!hasAlarm) return false
        return listOf(
            "cancela", "cancelar", "quita", "quitar", "borra", "borrar",
            "elimina", "eliminar", "desactiva", "desactivar", "anula", "anular",
        ).any { t.contains(it) }
    }

    private fun isChange(t: String): Boolean {
        val hasAlarm = t.contains("alarma") || t.contains("despertador")
        if (!hasAlarm) return false
        return listOf(
            "cambia", "cambiar", "modifica", "modificar", "pasa", "pasar",
            "mueve", "mover", "retrasa", "adelanta",
        ).any { t.contains(it) }
    }

    private fun isSet(t: String): Boolean {
        if (t.contains("alarma") || t.contains("despertador")) {
            return listOf(
                "pon", "poner", "crea", "crear", "programa", "programar",
                "activa", "activar", "nueva", "nuevo", "quiero", "necesito",
            ).any { t.contains(it) } ||
                t.startsWith("alarma a") ||
                t.startsWith("despertador a") ||
                t.contains("despiertame") ||
                t.contains("despierta me")
        }
        return t.contains("despiertame") || t.contains("despierta me")
    }

    private fun parseRelative(t: String): ParsedClockTime? {
        Regex("""en\s+(\d+)\s+minutos?""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { mins ->
            val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, mins.coerceIn(1, 24 * 60)) }
            return ParsedClockTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        }
        if (t.contains("en media hora") || t.contains("en 30 minutos")) {
            val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 30) }
            return ParsedClockTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        }
        if (t.contains("en una hora") || t.contains("en 1 hora")) {
            val cal = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
            return ParsedClockTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        }
        return null
    }

    private fun extractAllTimes(t: String): List<ParsedClockTime> {
        val results = mutableListOf<ParsedClockTime>()
        // HH:MM / H:MM (prioridad)
        for (m in Regex("""\b(\d{1,2})[:\.](\d{2})\b""").findAll(t)) {
            val h = m.groupValues[1].toIntOrNull() ?: continue
            val min = m.groupValues[2].toIntOrNull() ?: continue
            if (h in 0..23 && min in 0..59) results += ParsedClockTime(h, min)
        }
        if (results.isNotEmpty()) return results.distinct()

        // a las N (y media/cuarto)
        val spoken = Regex(
            """(?:a\s+las?\s+|las?\s+)(\d{1,2})(?:\s+y\s+(media|cuarto|\d{1,2}))?""" +
                """(?:\s+de\s+la\s+(manana|tarde|noche))?""",
        )
        for (m in spoken.findAll(t)) {
            var h = m.groupValues[1].toIntOrNull() ?: continue
            val frac = m.groupValues.getOrNull(2).orEmpty()
            val period = m.groupValues.getOrNull(3).orEmpty()
            val min = when (frac) {
                "media" -> 30
                "cuarto" -> 15
                "" -> 0
                else -> frac.toIntOrNull()?.coerceIn(0, 59) ?: 0
            }
            h = applyPeriod(h, period)
            if (h in 0..23) results += ParsedClockTime(h, min)
        }
        // menos cuarto: a las 8 menos cuarto → 7:45
        Regex("""(?:a\s+las?\s+|las?\s+)(\d{1,2})\s+menos\s+cuarto""")
            .findAll(t)
            .forEach { m ->
                var h = m.groupValues[1].toIntOrNull() ?: return@forEach
                h = (h - 1).mod(24)
                results += ParsedClockTime(h, 45)
            }
        return results.distinct()
    }

    private fun extractTime(t: String): ParsedClockTime? = extractAllTimes(t).lastOrNull()

    private fun applyPeriod(hour12or24: Int, period: String): Int {
        if (hour12or24 in 13..23) return hour12or24
        return when (period) {
            "tarde", "noche" -> if (hour12or24 in 1..11) hour12or24 + 12 else hour12or24
            "manana" -> if (hour12or24 == 12) 0 else hour12or24
            else -> {
                // Sin periodo: si es 1–6 y ya pasó hoy por la mañana, asumir tarde
                // Dejamos 24h tal cual (7 = 07:00)
                hour12or24
            }
        }
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("despertame", "despiertame")
            .replace(Regex("[^a-z0-9:\\s.]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val STOP_WHILE_RINGING = listOf(
            "para", "apaga", "apagala", "basta", "silencio", "calla",
            "quita", "stop", "ya esta", "vale ya",
        )
    }
}
