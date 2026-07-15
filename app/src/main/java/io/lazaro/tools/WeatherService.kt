package io.lazaro.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeatherService @Inject constructor() {

    suspend fun forecastForOjen(targetDate: LocalDate): WeatherForecast? = withContext(Dispatchers.IO) {
        val today = LocalDate.now(ZONE)
        val daysAhead = (targetDate.toEpochDay() - today.toEpochDay()).toInt()
        if (daysAhead < 0 || daysAhead > MAX_FORECAST_DAYS) return@withContext null

        try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$OJEN_LAT&longitude=$OJEN_LON" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                    "precipitation_probability_max,precipitation_sum,wind_speed_10m_max" +
                    "&timezone=Europe%2FMadrid&forecast_days=${MAX_FORECAST_DAYS + 1}",
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val json = JSONObject(body)
            val daily = json.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val maxTemps = daily.getJSONArray("temperature_2m_max")
            val minTemps = daily.getJSONArray("temperature_2m_min")
            val rainProb = daily.getJSONArray("precipitation_probability_max")
            val rainSum = daily.getJSONArray("precipitation_sum")
            val wind = daily.getJSONArray("wind_speed_10m_max")

            val targetIso = targetDate.toString()
            var index = -1
            for (i in 0 until dates.length()) {
                if (dates.getString(i) == targetIso) {
                    index = i
                    break
                }
            }
            if (index < 0) return@withContext null

            WeatherForecast(
                date = targetDate,
                description = describeWeatherCode(codes.getInt(index)),
                maxCelsius = maxTemps.getDouble(index),
                minCelsius = minTemps.getDouble(index),
                rainProbabilityPercent = rainProb.optInt(index, 0),
                rainMillimeters = rainSum.optDouble(index, 0.0),
                windKmh = wind.optDouble(index, 0.0),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun resolveTargetDate(userText: String): LocalDate {
        val text = normalize(userText)
        val today = LocalDate.now(ZONE)

        DATE_PATTERN.find(text)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            val month = match.groupValues[2].toIntOrNull() ?: return@let
            val year = match.groupValues.getOrNull(3)?.toIntOrNull() ?: today.year
            return runCatching { LocalDate.of(year, month, day) }.getOrDefault(today)
        }

        return when {
            text.contains("pasado manana") -> today.plusDays(2)
            text.contains("manana") -> today.plusDays(1)
            text.contains("hoy") || text.contains("este dia") -> today
            else -> {
                val weekday = WEEKDAY_TRIGGERS.entries.firstOrNull { text.contains(it.key) }?.value
                if (weekday != null) {
                    nextOrSameWeekday(today, weekday)
                } else {
                    today
                }
            }
        }
    }

    fun formatForecast(forecast: WeatherForecast): String {
        val dayLabel = dayLabelFor(forecast.date)
        val max = forecast.maxCelsius.toInt()
        val min = forecast.minCelsius.toInt()
        val rain = when {
            forecast.rainProbabilityPercent >= 60 || forecast.rainMillimeters >= 2.0 ->
                "Probable lluvia, ${forecast.rainProbabilityPercent} por ciento."
            forecast.rainProbabilityPercent >= 30 ->
                "Puede llover, ${forecast.rainProbabilityPercent} por ciento."
            else -> "Poca probabilidad de lluvia."
        }
        return "En Ojén $dayLabel: ${forecast.description}. " +
            "Máxima $max grados, mínima $min. $rain"
    }

    private fun dayLabelFor(date: LocalDate): String {
        val today = LocalDate.now(ZONE)
        return when (date) {
            today -> "hoy"
            today.plusDays(1) -> "mañana"
            today.plusDays(2) -> "pasado mañana"
            else -> {
                val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, LOCALE)
                "el $weekday ${date.format(DAY_MONTH)}"
            }
        }
    }

    private fun nextOrSameWeekday(from: LocalDate, target: DayOfWeek): LocalDate {
        var date = from
        repeat(7) {
            if (date.dayOfWeek == target) return date
            date = date.plusDays(1)
        }
        return from
    }

    private fun describeWeatherCode(code: Int): String = when (code) {
        0 -> "despejado"
        1, 2, 3 -> "parcialmente nublado"
        45, 48 -> "niebla"
        51, 53, 55 -> "llovizna"
        56, 57 -> "llovizna helada"
        61, 63, 65 -> "lluvia"
        66, 67 -> "lluvia helada"
        71, 73, 75 -> "nieve"
        77 -> "granizo de nieve"
        80, 81, 82 -> "chubascos"
        85, 86 -> "chubascos de nieve"
        95 -> "tormenta"
        96, 99 -> "tormenta con granizo"
        else -> "tiempo variable"
    }

    private fun normalize(text: String): String {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9\\s/]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private const val OJEN_LAT = 36.5649
        private const val OJEN_LON = -4.8559
        private const val MAX_FORECAST_DAYS = 14
        private val ZONE = ZoneId.of("Europe/Madrid")
        private val LOCALE = Locale("es", "ES")
        private val DAY_MONTH = DateTimeFormatter.ofPattern("d 'de' MMMM", LOCALE)
        private val DATE_PATTERN = Regex("""(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?""")

        private val WEEKDAY_TRIGGERS = mapOf(
            "lunes" to DayOfWeek.MONDAY,
            "martes" to DayOfWeek.TUESDAY,
            "miercoles" to DayOfWeek.WEDNESDAY,
            "jueves" to DayOfWeek.THURSDAY,
            "viernes" to DayOfWeek.FRIDAY,
            "sabado" to DayOfWeek.SATURDAY,
            "domingo" to DayOfWeek.SUNDAY,
        )
    }
}

data class WeatherForecast(
    val date: LocalDate,
    val description: String,
    val maxCelsius: Double,
    val minCelsius: Double,
    val rainProbabilityPercent: Int,
    val rainMillimeters: Double,
    val windKmh: Double,
)
