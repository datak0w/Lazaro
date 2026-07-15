package io.lazaro.tools

import io.lazaro.actions.ActionResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeatherAction @Inject constructor(
    private val weatherService: WeatherService,
    private val weatherIntentDetector: WeatherIntentDetector,
) {
    suspend fun tryPrepare(userText: String): ActionResult? {
        if (!weatherIntentDetector.detect(userText)) return null
        val targetDate = weatherService.resolveTargetDate(userText)
        val forecast = weatherService.forecastForOjen(targetDate)
            ?: return ActionResult.Error(
                "No pude obtener la previsión para Ojén en esa fecha. Prueba con hoy o mañana.",
            )
        return ActionResult.Success(weatherService.formatForecast(forecast))
    }
}
