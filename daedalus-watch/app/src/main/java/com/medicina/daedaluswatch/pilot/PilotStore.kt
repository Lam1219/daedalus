package com.medicina.daedaluswatch.pilot

import android.content.Context
import com.medicina.shared.pilot.CheckinDay
import com.medicina.shared.pilot.PhoneStatus
import com.medicina.shared.pilot.Pilot
import com.medicina.shared.pilot.PilotSettings
import java.time.LocalDate

/**
 * The watch's pilot state: the settings sent by the phone, the press counter, today's check-in
 * count, the last pocket reminder, and what the phone last said about its earbuds and steps.
 */
object PilotStore {
    private const val PREFS = "pilot"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_PRESS_INDEX = "press_index"
    private const val KEY_CHECKIN_DAY = "checkin_day"
    private const val KEY_CHECKIN_COUNT = "checkin_count"
    private const val KEY_LAST_REMINDER = "last_reminder_ms"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(c: Context): PilotSettings =
        prefs(c).getString(KEY_SETTINGS, null)?.let { PilotSettings.decode(it) } ?: PilotSettings()

    /** Study mode is on with a valid code and seed. Off: the watch behaves as without the pilot. */
    fun active(c: Context): Boolean = settings(c).active

    fun setSettings(c: Context, s: PilotSettings) {
        val before = settings(c)
        val e = prefs(c).edit().putString(KEY_SETTINGS, s.encode())
        // A new participant or seed starts a new schedule: count presses from 0 again.
        if (before.participant != s.participant || before.seed != s.seed) e.putLong(KEY_PRESS_INDEX, 0)
        e.commit()
    }

    /** This press's index (0 for the first after enrolment); the counter moves on before the beat starts. */
    @Synchronized
    fun takePressIndex(c: Context): Long {
        val i = prefs(c).getLong(KEY_PRESS_INDEX, 0)
        prefs(c).edit().putLong(KEY_PRESS_INDEX, i + 1).commit()
        return i
    }

    fun checkinDay(c: Context): CheckinDay {
        val p = prefs(c)
        val day = p.getLong(KEY_CHECKIN_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { LocalDate.ofEpochDay(it) }
        return CheckinDay(day, p.getInt(KEY_CHECKIN_COUNT, 0))
    }

    fun setCheckinDay(c: Context, d: CheckinDay) {
        prefs(c).edit().putLong(KEY_CHECKIN_DAY, d.askedOn?.toEpochDay() ?: Long.MIN_VALUE).putInt(KEY_CHECKIN_COUNT, d.asked).apply()
    }

    fun lastReminderMs(c: Context): Long? = prefs(c).getLong(KEY_LAST_REMINDER, -1).takeIf { it >= 0 }
    fun setLastReminderMs(c: Context, ms: Long) = prefs(c).edit().putLong(KEY_LAST_REMINDER, ms).apply()

    // --- What the phone last reported (memory only; the watch asks again when its screen opens) ---

    @Volatile var phoneNodeId: String? = null
    @Volatile var phoneEarbuds = false
        private set
    /** Watch-clock time of the phone's last counted step, or null. */
    @Volatile var lastPhoneStepMs: Long? = null
        private set

    fun onPhoneStatus(s: PhoneStatus, receivedAtMs: Long, nodeId: String?) {
        if (nodeId != null) phoneNodeId = nodeId
        phoneEarbuds = s.earbuds
        val step = s.lastStepAgoMs?.let { receivedAtMs - it }
        // Keep the newest step: a late reply can't erase a step reported since.
        if (step != null && (lastPhoneStepMs == null || step > lastPhoneStepMs!!)) lastPhoneStepMs = step
    }

    /** "The phone counted steps in the last minute": it is being carried, not lying on a table. */
    fun phoneCarried(nowMs: Long): Boolean =
        lastPhoneStepMs?.let { nowMs - it <= Pilot.PHONE_CARRIED_WINDOW_S * 1000L } ?: false
}
