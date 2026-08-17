package io.lazaro.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.AndroidEntryPoint
import io.lazaro.service.AssistantForegroundService
import io.lazaro.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var textToSpeechManager: TextToSpeechManager
    @Inject lateinit var alarmRepository: AlarmRepository

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_FIRE) return
        val id = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val hour = intent.getIntExtra(EXTRA_HOUR, 0)
        val minute = intent.getIntExtra(EXTRA_MINUTE, 0)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "Alarma" }

        AlarmRingingCoordinator.markRinging(id)
        vibrate(context)

        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                if (id > 0) {
                    alarmRepository.remove(id)
                }
                // Asegurar FGS activo para escuchar «para la alarma»
                try {
                    androidx.core.content.ContextCompat.startForegroundService(
                        context,
                        Intent(context, AssistantForegroundService::class.java),
                    )
                } catch (_: Exception) {
                }
                textToSpeechManager.initialize(Locale("es", "ES"))
                val timeSpoken = speakClock(hour, minute)
                textToSpeechManager.speak("$label. Son las $timeSpoken. Di para la alarma para apagarla.")
            } finally {
                pending.finish()
            }
        }
    }

    private fun vibrate(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500, 300, 500), -1),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 500, 300, 500, 300, 500), -1)
            }
        } catch (_: Exception) {
        }
    }

    private fun speakClock(hour: Int, minute: Int): String {
        val h = when (hour) {
            0 -> "cero"
            1 -> "una"
            21 -> "veintiuna"
            else -> CARDINALS[hour] ?: hour.toString()
        }
        if (minute == 0) return "$h en punto"
        val m = CARDINALS[minute] ?: minute.toString()
        return "$h y $m"
    }

    companion object {
        const val ACTION_FIRE = "io.lazaro.alarm.FIRE"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
        const val EXTRA_LABEL = "label"

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
