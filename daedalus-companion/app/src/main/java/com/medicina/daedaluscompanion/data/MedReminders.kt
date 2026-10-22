package com.medicina.daedaluscompanion.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.ui.MainActivity
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

private const val TAG = "MedReminders"

/**
 * Daily dose reminders for the medication schedule. Each dose time gets an exact alarm that
 * also fires in Doze (USE_EXACT_ALARM on Android 13+, SCHEDULE_EXACT_ALARM on 12). The next
 * time is worked out by calendar day, so it stays at the same clock time across daylight-saving
 * changes, and alarms are set again after a reboot or a time or time-zone change. The
 * notification also shows on the paired watch through Wear OS notification bridging.
 */
object MedReminders {
    private const val CHANNEL = "daedalus_dose_reminders"
    const val ACTION_REMIND = "com.medicina.daedaluscompanion.action.DOSE_REMIND"
    const val ACTION_TAKEN = "com.medicina.daedaluscompanion.action.DOSE_TAKEN"
    const val EXTRA_SLOT = "slot_id"
    const val EXTRA_OPEN_TAB = "open_tab"
    private const val PREFS = "med_reminder_alarms"
    private const val KEY_CODES = "codes"
    /** Without exact alarms (permission revoked on Android 12), allow this much delay. */
    private const val WINDOW_MS = 10 * 60 * 1000L

    /** Cancels every reminder alarm, then sets the next one for each dose if reminders are on. */
    fun reschedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_CODES, emptySet())!!.forEach { code ->
            am.cancel(alarmIntent(context, code.toInt(), null))
        }
        val store = LocalStore(context)
        if (!store.getBoolean(LocalStore.MED_REMINDERS, true)) {
            prefs.edit().putStringSet(KEY_CODES, emptySet()).apply()
            return
        }
        val now = System.currentTimeMillis()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        val codes = mutableSetOf<String>()
        store.schedule().forEach { dose ->
            val at = nextTime(dose.hour, dose.minute, now, ZoneId.systemDefault())
            val code = dose.id.hashCode()
            val intent = alarmIntent(context, code, dose.id)
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            else am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, intent)
            codes += code.toString()
        }
        prefs.edit().putStringSet(KEY_CODES, codes).apply()
        Log.i(TAG, "Scheduled ${codes.size} dose reminder(s)")
    }

    /**
     * The next [hour]:[minute] after [now] in [zone], counted in calendar days rather than 24-hour
     * steps, so a reminder stays at 8:00 on the day the clocks change. A time that doesn't exist
     * that day (skipped by a spring-forward) moves to just after the gap.
     */
    fun nextTime(hour: Int, minute: Int, now: Long, zone: ZoneId): Long {
        val nowZoned = Instant.ofEpochMilli(now).atZone(zone)
        var at = ZonedDateTime.of(nowZoned.toLocalDate(), LocalTime.of(hour, minute), zone)
        if (!at.isAfter(nowZoned)) at = ZonedDateTime.of(nowZoned.toLocalDate().plusDays(1), LocalTime.of(hour, minute), zone)
        return at.toInstant().toEpochMilli()
    }

    private fun alarmIntent(context: Context, code: Int, slotId: String?): PendingIntent {
        val intent = Intent(context, MedReminderReceiver::class.java).setAction(ACTION_REMIND)
        if (slotId != null) intent.putExtra(EXTRA_SLOT, slotId)
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun showReminder(context: Context, dose: ScheduledDose) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val code = dose.id.hashCode()
        val open = PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, "meds")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val taken = PendingIntent.getBroadcast(
            context, code,
            Intent(context, MedReminderReceiver::class.java).setAction(ACTION_TAKEN).putExtra(EXTRA_SLOT, dose.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = listOf(dose.name, dose.dosage).filter { it.isNotBlank() }.joinToString(" · ")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_pill)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.reminder_taken), taken)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(code, notification) }
            .onFailure { Log.w(TAG, "Reminder not shown (notification permission?): ${it.message}") }
    }

    /** Logs [dose] as taken now, unless it is already logged today. Returns the new log, if any. */
    fun markTaken(context: Context, dose: ScheduledDose): MedicationLog? {
        val store = LocalStore(context)
        val now = System.currentTimeMillis()
        val log = if (store.takenLog(dose, now) == null) {
            MedicationLog(dose.name, dose.dosage, now, dose.id).also { store.addMedication(it) }
        } else null
        NotificationManagerCompat.from(context).cancel(dose.id.hashCode())
        return log
    }
}

/** Fires a dose reminder, or logs a dose from the notification's "Mark taken" action. */
class MedReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = LocalStore(context)
        val dose = store.schedule().firstOrNull { it.id == intent.getStringExtra(MedReminders.EXTRA_SLOT) }
        when (intent.action) {
            MedReminders.ACTION_REMIND -> {
                if (dose != null && store.getBoolean(LocalStore.MED_REMINDERS, true) &&
                    store.takenLog(dose, System.currentTimeMillis()) == null
                ) {
                    MedReminders.showReminder(context, dose)
                }
                MedReminders.reschedule(context) // next day's alarm
            }
            MedReminders.ACTION_TAKEN -> if (dose != null) MedReminders.markTaken(context, dose)
        }
    }
}

/**
 * Sets the alarms again when they are cleared (reboot, app update) or would be wrong: the clock
 * or time zone changed, or exact alarms were allowed again.
 */
class MedReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in RESCHEDULE_ACTIONS) MedReminders.reschedule(context)
    }

    private companion object {
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED, // "android.intent.action.TIME_SET"
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
