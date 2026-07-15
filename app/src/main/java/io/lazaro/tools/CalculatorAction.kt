package io.lazaro.tools

import io.lazaro.actions.ActionResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalculatorAction @Inject constructor(
    private val calculatorIntentDetector: CalculatorIntentDetector,
) {
    fun tryPrepare(userText: String): ActionResult? {
        if (!calculatorIntentDetector.detect(userText)) return null
        val result = VoiceCalculator.tryEvaluate(userText)
            ?: return ActionResult.Error("No he entendido la operación. Di por ejemplo: cuánto es 18 por 37.")
        return ActionResult.Success(result)
    }
}
