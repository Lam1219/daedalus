package com.medicina.daedaluscompanion.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/** A logged dose. [slotId] links it to a scheduled dose time (null = unscheduled / extra dose). */
data class MedicationLog(val name: String, val dosage: String, val timestamp: Long, val slotId: String? = null) {
    /** Stable ID for backup (same log → same row). */
    val id: String get() = UUID.nameUUIDFromBytes("dose|$timestamp|$name".toByteArray()).toString()
}

/** A daily dose time set in Profile → Medication schedule. */
data class ScheduledDose(
    val id: String,
    val name: String,
    val dosage: String,
    val hour: Int,
    val minute: Int,
    /** When the dose time was added; days before it don't count toward adherence. */
    val createdAt: Long = 0L,
) {
    val minutesOfDay get() = hour * 60 + minute

    /** This dose's time on the day containing [day]. */
    fun timeOn(day: Long): Long = Calendar.getInstance().apply {
        timeInMillis = day; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

enum class Feeling { GOOD, WEARING_OFF, UNSURE }

data class FeelingCheckIn(val feeling: Feeling, val timestamp: Long) {
    /** Stable ID for backup (same check-in → same row). */
    val id: String get() = UUID.nameUUIDFromBytes("checkin|$timestamp|${feeling.name}".toByteArray()).toString()
}

/**
 * Small on-device store (SharedPreferences + JSON). The personal profile is also kept in
 * Supabase (`profiles`) for signed-in accounts.
 */
class LocalStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("daedalus_local", Context.MODE_PRIVATE)

    /** Sends the change to the signed-in account (no-op for guests or with backup off). */
    private fun changed() = CloudBackup.syncAsync(appContext)

    fun medications(): List<MedicationLog> = readArray(KEY_MEDS).map {
        MedicationLog(it.getString("name"), it.optString("dosage"), it.getLong("ts"), it.optString("slot").ifBlank { null })
    }.sortedByDescending { it.timestamp }

    fun addMedication(log: MedicationLog) {
        append(KEY_MEDS, JSONObject().apply {
            put("name", log.name); put("dosage", log.dosage); put("ts", log.timestamp)
            if (log.slotId != null) put("slot", log.slotId)
        })
        changed()
    }

    // --- Medication schedule ---

    fun schedule(): List<ScheduledDose> = readArray(KEY_SCHEDULE).map {
        ScheduledDose(it.getString("id"), it.getString("name"), it.optString("dosage"), it.getInt("h"), it.getInt("m"), it.optLong("created"))
    }.sortedBy { it.minutesOfDay }

    fun addScheduledDose(name: String, dosage: String, hour: Int, minute: Int): ScheduledDose {
        val dose = ScheduledDose(UUID.randomUUID().toString(), name, dosage, hour, minute, System.currentTimeMillis())
        append(KEY_SCHEDULE, JSONObject().apply {
            put("id", dose.id); put("name", name); put("dosage", dosage); put("h", hour); put("m", minute); put("created", dose.createdAt)
        })
        changed()
        return dose
    }

    fun removeScheduledDose(id: String) {
        val kept = JSONArray()
        readArray(KEY_SCHEDULE).filter { it.getString("id") != id }.forEach { kept.put(it) }
        prefs.edit().putString(KEY_SCHEDULE, kept.toString()).apply()
        changed()
    }

    /** The log that marks [dose] as taken on the day containing [day], if any. */
    fun takenLog(dose: ScheduledDose, day: Long, logs: List<MedicationLog> = medications()): MedicationLog? =
        logs.firstOrNull { it.slotId == dose.id && sameDay(it.timestamp, day) }

    fun checkIns(): List<FeelingCheckIn> = readArray(KEY_FEELINGS).mapNotNull {
        runCatching { FeelingCheckIn(Feeling.valueOf(it.getString("feeling")), it.getLong("ts")) }.getOrNull()
    }.sortedByDescending { it.timestamp }

    fun addCheckIn(checkIn: FeelingCheckIn) {
        append(KEY_FEELINGS, JSONObject().apply {
            put("feeling", checkIn.feeling.name); put("ts", checkIn.timestamp)
        })
        changed()
    }

    // --- Backup and deletion (Profile → Your data) ---

    /** Whether data is backed up to the signed-in account. On by default. */
    var cloudBackup: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_BACKUP, true)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_BACKUP, value).apply()

    /** Deletes the health history kept on this phone: doses, check-ins, schedule and profile. Settings stay. */
    fun clearHealthData() {
        prefs.edit()
            .remove(KEY_MEDS).remove(KEY_FEELINGS).remove(KEY_SCHEDULE)
            .remove(KEY_PROFILE).remove(KEY_PROFILE_OWNER).remove(KEY_PROFILE_PENDING)
            .apply()
    }

    // --- Personal information (first-sign-in setup) ---

    /** The profile saved for [owner] (the account email), or null if setup hasn't been done on this phone. */
    fun personalProfile(owner: String?): PersonalProfile? {
        if (prefs.getString(KEY_PROFILE_OWNER, null) != owner) return null
        val json = prefs.getString(KEY_PROFILE, null) ?: return null
        return runCatching { PersonalProfile.fromJson(JSONObject(json)) }.getOrNull()
    }

    /** [uploaded] = false means it still has to be sent to Supabase. */
    fun savePersonalProfile(owner: String?, profile: PersonalProfile, uploaded: Boolean) {
        prefs.edit()
            .putString(KEY_PROFILE, profile.toJson().toString())
            .putString(KEY_PROFILE_OWNER, owner)
            .putBoolean(KEY_PROFILE_PENDING, !uploaded)
            .apply()
    }

    fun profileUploadPending() = prefs.getBoolean(KEY_PROFILE_PENDING, false)
    fun setProfileUploadPending(pending: Boolean) = prefs.edit().putBoolean(KEY_PROFILE_PENDING, pending).apply()

    fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    fun setBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    fun setInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    fun getFloat(key: String, default: Float) = prefs.getFloat(key, default)
    fun setFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()

    private fun readArray(key: String): List<JSONObject> {
        val array = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun append(key: String, item: JSONObject) {
        val array = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        array.put(item)
        prefs.edit().putString(key, array.toString()).apply()
    }

    companion object {
        private const val KEY_MEDS = "medications"
        private const val KEY_FEELINGS = "feelings"
        private const val KEY_SCHEDULE = "med_schedule"
        private const val KEY_PROFILE = "personal_profile"
        private const val KEY_PROFILE_OWNER = "personal_profile_owner"
        private const val KEY_PROFILE_PENDING = "personal_profile_pending"
        private const val KEY_CLOUD_BACKUP = "cloud_backup"
        const val CUE_WALKING = "cue_walking"
        const val CUE_TREMOR = "cue_tremor"
        const val CUE_BREATHING = "cue_breathing"
        const val CUE_STAND = "cue_stand"
        const val MED_REMINDERS = "med_reminders"
        /** Pulse rate per cue mode, e.g. "cue_bpm_walking". */
        fun cueBpmKey(modeId: String) = "cue_bpm_$modeId"

        fun sameDay(a: Long, b: Long): Boolean {
            val ca = Calendar.getInstance().apply { timeInMillis = a }
            val cb = Calendar.getInstance().apply { timeInMillis = b }
            return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
        }
    }
}
