package io.lazaro.alarm

import io.lazaro.actions.ActionResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmAction @Inject constructor(
    private val detector: AlarmIntentDetector,
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
) {
    suspend fun tryPrepare(userText: String): ActionResult? {
        if (detector.isAlarmStopWhileRinging(userText)) {
            return stopRinging()
        }
        val intent = detector.detect(userText) ?: return null
        return when (intent) {
            is AlarmVoiceIntent.Set -> setAlarm(intent.time.hour, intent.time.minute, intent.label)
            is AlarmVoiceIntent.Change -> changeAlarm(intent.from, intent.to)
            is AlarmVoiceIntent.Cancel -> cancelAlarm(intent.time)
            AlarmVoiceIntent.StopRinging -> stopRinging()
            AlarmVoiceIntent.List -> listAlarms()
        }
    }

    suspend fun setAlarm(hour: Int, minute: Int, label: String = "Alarma"): ActionResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return ActionResult.Error("No he entendido la hora de la alarma.")
        }
        repository.findByTime(hour, minute)?.let { existing ->
            return ActionResult.Success(
                "Ya tienes una alarma a las ${speakTime(existing.hour, existing.minute)}.",
            )
        }
        val id = System.currentTimeMillis()
        val alarm = LazaroAlarm(
            id = id,
            hour = hour,
            minute = minute,
            label = label.ifBlank { "Alarma" },
            enabled = true,
            triggerAtEpochMs = scheduler.nextTriggerEpochMs(hour, minute),
        )
        repository.upsert(alarm)
        scheduler.schedule(alarm)
        return ActionResult.Success(
            "Alarma puesta a las ${speakTime(hour, minute)}.",
        )
    }

    suspend fun changeAlarm(from: ParsedClockTime?, to: ParsedClockTime): ActionResult {
        val target = if (from != null) {
            repository.findByTime(from.hour, from.minute)
        } else {
            repository.nextEnabled()
        } ?: return ActionResult.Error(
            if (from != null) {
                "No encuentro la alarma de las ${speakTime(from.hour, from.minute)}."
            } else {
                "No hay ninguna alarma que cambiar."
            },
        )

        scheduler.cancel(target)
        repository.remove(target.id)
        return setAlarm(to.hour, to.minute, target.label)
            .let { result ->
                when (result) {
                    is ActionResult.Success -> ActionResult.Success(
                        "Alarma cambiada a las ${speakTime(to.hour, to.minute)}.",
                    )
                    else -> result
                }
            }
    }

    suspend fun cancelAlarm(time: ParsedClockTime?): ActionResult {
        val target = if (time != null) {
            repository.findByTime(time.hour, time.minute)
        } else {
            repository.nextEnabled()
        } ?: return ActionResult.Error(
            if (time != null) {
                "No hay alarma a las ${speakTime(time.hour, time.minute)}."
            } else {
                "No tienes alarmas pendientes."
            },
        )
        scheduler.cancel(target)
        repository.remove(target.id)
        return ActionResult.Success(
            "Alarma de las ${speakTime(target.hour, target.minute)} cancelada.",
        )
    }

    fun stopRinging(): ActionResult {
        val id = AlarmRingingCoordinator.currentAlarmId()
        AlarmRingingCoordinator.clear()
        scheduler.dismissRingingOrMatching()
        // Silenciar TTS de la alarma lo hace el caller (AssistantController)
        return ActionResult.Success(
            if (id != null) "Alarma apagada." else "Vale, apago la alarma.",
        )
    }

    suspend fun listAlarms(): ActionResult {
        val alarms = repository.enabledAlarms()
        if (alarms.isEmpty()) {
            return ActionResult.Success("No tienes alarmas puestas.")
        }
        val spoken = alarms.take(5).joinToString(". ") { alarm ->
            "a las ${speakTime(alarm.hour, alarm.minute)}"
        }
        return ActionResult.Success(
            if (alarms.size == 1) {
                "Tienes una alarma $spoken."
            } else {
                "Tienes ${alarms.size} alarmas: $spoken."
            },
        )
    }

    suspend fun rescheduleAfterBoot() {
        val alarms = repository.enabledAlarms().map { alarm ->
            alarm.copy(triggerAtEpochMs = scheduler.nextTriggerEpochMs(alarm.hour, alarm.minute))
        }
        repository.saveAll(alarms)
        scheduler.rescheduleAll(alarms)
    }

    private fun speakTime(hour: Int, minute: Int): String {
        val h = when (hour) {
            0 -> "cero"
            1 -> "una"
            21 -> "veintiuna"
            else -> CARDINALS[hour] ?: hour.toString()
        }
        if (minute == 0) return "$h en punto"
        if (minute == 15) return "$h y cuarto"
        if (minute == 30) return "$h y media"
        if (minute == 45) {
            val next = (hour + 1) % 24
            val nh = when (next) {
                0 -> "cero"
                1 -> "una"
                21 -> "veintiuna"
                else -> CARDINALS[next] ?: next.toString()
            }
            return "$nh menos cuarto"
        }
        val m = CARDINALS[minute] ?: minute.toString()
        return "$h y $m"
    }

    companion object {
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
