package io.lazaro.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun nextTriggerEpochMs(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            if (timeInMillis <= System.currentTimeMillis() + 15_000L) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    fun schedule(alarm: LazaroAlarm) {
        scheduleExact(alarm)
        mirrorToSystemClock(alarm.hour, alarm.minute, alarm.label)
    }

    fun cancel(alarm: LazaroAlarm) {
        cancelExact(alarm.id)
        dismissSystemAlarm(alarm.hour, alarm.minute)
    }

    fun rescheduleAll(alarms: List<LazaroAlarm>) {
        for (alarm in alarms.filter { it.enabled }) {
            val refreshed = alarm.copy(
                triggerAtEpochMs = nextTriggerEpochMs(alarm.hour, alarm.minute),
            )
            scheduleExact(refreshed)
        }
    }

    fun dismissRingingOrMatching(hour: Int? = null, minute: Int? = null) {
        try {
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (hour != null && minute != null) {
                    putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                } else {
                    putExtra(
                        AlarmClock.EXTRA_ALARM_SEARCH_MODE,
                        AlarmClock.ALARM_SEARCH_MODE_NEXT,
                    )
                }
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            // Algunas ROMs no exponen dismiss
        }
    }

    private fun scheduleExact(alarm: LazaroAlarm) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = alarm.triggerAtEpochMs
        val showIntent = PendingIntent.getActivity(
            context,
            alarm.id.toInt(),
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val broadcast = PendingIntent.getBroadcast(
            context,
            alarm.id.toInt(),
            Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_FIRE
                putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id)
                putExtra(AlarmReceiver.EXTRA_HOUR, alarm.hour)
                putExtra(AlarmReceiver.EXTRA_MINUTE, alarm.minute)
                putExtra(AlarmReceiver.EXTRA_LABEL, alarm.label)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // Caer a inexacto si el usuario no ha concedido alarmas exactas
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, broadcast)
            } else {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), broadcast)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, broadcast)
        }
    }

    private fun cancelExact(alarmId: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val broadcast = PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_FIRE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(broadcast)
    }

    private fun mirrorToSystemClock(hour: Int, minute: Int, label: String) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                putExtra(AlarmClock.EXTRA_VIBRATE, true)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            // Sin reloj del sistema compatible
        }
    }

    private fun dismissSystemAlarm(hour: Int, minute: Int) {
        dismissRingingOrMatching(hour, minute)
    }
}
