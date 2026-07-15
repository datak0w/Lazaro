package io.lazaro.tools

import io.lazaro.actions.ActionResult
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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

    fun formatCurrentTime(): String {
        val now = ZonedDateTime.now(ZONE)
        val spoken = SPEECH_FORMATTER.format(now)
        val period = when (now.hour) {
            in 6..11 -> "de la mañana"
            in 12..20 -> "de la tarde"
            else -> "de la noche"
        }
        return "Son las $spoken $period."
    }

    companion object {
        private val ZONE = ZoneId.of("Europe/Madrid")
        private val SPEECH_FORMATTER = DateTimeFormatter.ofPattern("H:mm", Locale("es", "ES"))
    }
}
