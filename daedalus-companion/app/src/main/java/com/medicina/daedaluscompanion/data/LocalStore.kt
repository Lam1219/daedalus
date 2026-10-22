package com.medicina.daedaluscompanion.data

import android.content.Context
import com.medicina.daedaluscompanion.ui.screens.sessionsDir
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
 * Small on-device store (SharedPreferences + JSON). The records belong to one account
 * ([owner], its Supabase user ID) or to a guest (null); see CloudBackup.
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
        clearTombstones(KEY_DELETED_DOSES, listOf(log.id)) // undoing a delete brings it back
        changed()
    }

    /** Removes a logged dose (undo, or delete from the list); backup deletes it from the account too. */
    fun removeMedication(log: MedicationLog) {
        removeWhere(KEY_MEDS, KEY_DELETED_DOSES, log.id) {
            MedicationLog(it.getString("name"), it.optString("dosage"), it.getLong("ts")).id
        }
    }

    /** Doses removed on this phone that may still be in the account. */
    fun deletedDoseIds(): List<String> = tombstones(KEY_DELETED_DOSES)
    fun clearDeletedDoseIds(ids: List<String>) = clearTombstones(KEY_DELETED_DOSES, ids)

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

    fun removeScheduledDose(id: String) = removeWhere(KEY_SCHEDULE, KEY_DELETED_SCHEDULE, id) { it.getString("id") }

    /** Dose times removed on this phone that the account still has. */
    fun deletedScheduleIds(): List<String> = tombstones(KEY_DELETED_SCHEDULE)
    fun clearDeletedScheduleIds(ids: List<String>) = clearTombstones(KEY_DELETED_SCHEDULE, ids)

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
        clearTombstones(KEY_DELETED_CHECKINS, listOf(checkIn.id))
        changed()
    }

    /** Removes a check-in (undo); backup deletes it from the account too. */
    fun removeCheckIn(checkIn: FeelingCheckIn) {
        removeWhere(KEY_FEELINGS, KEY_DELETED_CHECKINS, checkIn.id) {
            FeelingCheckIn(Feeling.valueOf(it.getString("feeling")), it.getLong("ts")).id
        }
    }

    /** Check-ins removed on this phone that may still be in the account. */
    fun deletedCheckInIds(): List<String> = tombstones(KEY_DELETED_CHECKINS)
    fun clearDeletedCheckInIds(ids: List<String>) = clearTombstones(KEY_DELETED_CHECKINS, ids)

    // --- Backup and deletion (Profile → Your data) ---

    /** Whether data is backed up to the signed-in account. On by default. */
    var cloudBackup: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_BACKUP, true)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_BACKUP, value).apply()

    /** Deletes the health history kept on this phone: doses, check-ins, schedule, profile and
     *  session files. Settings stay. */
    fun clearHealthData() {
        prefs.edit()
            .remove(KEY_MEDS).remove(KEY_FEELINGS).remove(KEY_SCHEDULE)
            .remove(KEY_DELETED_SCHEDULE).remove(KEY_DELETED_DOSES).remove(KEY_DELETED_CHECKINS)
            .remove(KEY_PROFILE).remove(KEY_PROFILE_OWNER).remove(KEY_PROFILE_PENDING)
            .apply()
        sessionsDir(appContext).listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    // --- Account ownership ---

    /** The account these records belong to (Supabase user ID), or null for guest use. */
    var owner: String?
        get() = prefs.getString(KEY_OWNER, null)
        set(value) = prefs.edit().putString(KEY_OWNER, value).apply()

    /** Whether there's any health data on this phone (records or session files). */
    fun hasHealthData(): Boolean =
        medications().isNotEmpty() || checkIns().isNotEmpty() || schedule().isNotEmpty() ||
            sessionsDir(appContext).listFiles()?.any { it.isFile && it.extension == "csv" } == true

    /** Adds records restored from the account, skipping any already on this phone or removed from it. */
    fun mergeFromAccount(schedule: List<ScheduledDose>, medications: List<MedicationLog>, checkIns: List<FeelingCheckIn>) {
        val scheduleIds = schedule().map { it.id }.toSet() + deletedScheduleIds()
        val doseIds = medications().map { it.id }.toSet() + deletedDoseIds()
        val checkInIds = checkIns().map { it.id }.toSet() + deletedCheckInIds()
        val sched = JSONArray(prefs.getString(KEY_SCHEDULE, "[]"))
        schedule.filter { it.id !in scheduleIds }.forEach {
            sched.put(JSONObject().put("id", it.id).put("name", it.name).put("dosage", it.dosage).put("h", it.hour).put("m", it.minute).put("created", it.createdAt))
        }
        val meds = JSONArray(prefs.getString(KEY_MEDS, "[]"))
        medications.filter { it.id !in doseIds }.forEach {
            meds.put(JSONObject().put("name", it.name).put("dosage", it.dosage).put("ts", it.timestamp).apply { if (it.slotId != null) put("slot", it.slotId) })
        }
        val feel = JSONArray(prefs.getString(KEY_FEELINGS, "[]"))
        checkIns.filter { it.id !in checkInIds }.forEach { feel.put(JSONObject().put("feeling", it.feeling.name).put("ts", it.timestamp)) }
        prefs.edit().putString(KEY_SCHEDULE, sched.toString()).putString(KEY_MEDS, meds.toString()).putString(KEY_FEELINGS, feel.toString()).apply()
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

    /** Drops the items in [key] whose ID is [id] and records the ID so backup deletes it from the account. */
    private fun removeWhere(key: String, tombstoneKey: String, id: String, idOf: (JSONObject) -> String) {
        val kept = JSONArray()
        readArray(key).filter { runCatching { idOf(it) }.getOrNull() != id }.forEach { kept.put(it) }
        prefs.edit()
            .putString(key, kept.toString())
            .putString(tombstoneKey, JSONArray((tombstones(tombstoneKey) + id).distinct()).toString())
            .apply()
        changed()
    }

    private fun tombstones(key: String): List<String> {
        val a = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun clearTombstones(key: String, ids: List<String>) {
        prefs.edit().putString(key, JSONArray(tombstones(key) - ids.toSet()).toString()).apply()
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
        private const val KEY_OWNER = "owner"
        private const val KEY_DELETED_SCHEDULE = "deleted_schedule_ids"
        private const val KEY_DELETED_DOSES = "deleted_dose_ids"
        private const val KEY_DELETED_CHECKINS = "deleted_checkin_ids"
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
