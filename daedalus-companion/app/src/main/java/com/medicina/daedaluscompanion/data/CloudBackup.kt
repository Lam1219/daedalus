package com.medicina.daedaluscompanion.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.medicina.daedaluscompanion.auth.AuthState
import com.medicina.daedaluscompanion.ui.screens.extractDate
import com.medicina.daedaluscompanion.ui.screens.extractMode
import com.medicina.daedaluscompanion.ui.screens.sessionsDir
import com.medicina.daedaluscompanion.utils.DaedalusApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime

private const val TAG = "CloudBackup"

/**
 * Backs up the phone's health data to the signed-in account in Supabase, restores it, and
 * deletes it. Same rules as the iPhone app:
 *
 * - Phone data belongs to one account (LocalStore.owner) or to a guest. Only the owner's data is
 *   backed up; guest data is added to an account only if the person agrees (ProfileSetupGate).
 * - Backup: profile, medication schedule, dose logs, check-ins and watch session files. Runs at
 *   start, after each change and when a session arrives; whatever fails (offline) is retried later.
 * - Restore: the first time an account is used on this phone, its data is brought back from
 *   Supabase before anything is uploaded, so a new phone never overwrites the account.
 * - Deleting: free up space (phone copies of backed-up sessions), delete uploaded data
 *   (account only, turns backup off), delete everything (phone + account data, then sign out),
 *   or delete the account itself.
 */
object CloudBackup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun syncAsync(context: Context) {
        val app = context.applicationContext as? DaedalusApplication ?: return
        scope.launch { sync(app) }
    }

    /** Runs [block] in this object's own scope, so it finishes even if the screen that started it closes. */
    fun launch(block: suspend CloudBackup.() -> Unit) {
        scope.launch { block() }
    }

    /** Backs up everything not yet in the account. True when it's all there. */
    suspend fun sync(app: DaedalusApplication): Boolean = mutex.withLock { syncLocked(app) }

    /** The first time this account is used on this phone: brings its data back from Supabase. */
    suspend fun restoreIfNeeded(app: DaedalusApplication): Boolean = mutex.withLock { restoreLocked(app) }

    /**
     * Backs up what it can, then clears this phone's health data and signs out, so the next person
     * to use the phone starts empty. Without [force], keeps everything and returns false when some
     * data isn't backed up.
     */
    suspend fun signOutAndClear(app: DaedalusApplication, force: Boolean): Boolean = mutex.withLock {
        val backedUp = syncLocked(app)
        if (!backedUp && !force) return false
        LocalStore(app).run { clearHealthData(); owner = null }
        MedReminders.reschedule(app)
        app.auth.signOut()
        return true
    }

    /** Whether everything on this phone is in the account (used to word the sign-out warning). */
    suspend fun isFullyBackedUp(app: DaedalusApplication): Boolean = sync(app)

    /** Deletes the account itself, everything saved in it, and this phone's health data. */
    suspend fun deleteAccount(app: DaedalusApplication): Boolean = mutex.withLock {
        val userId = app.auth.userId() ?: return false
        if (!deleteUploadedLocked(app)) return false
        val token = app.auth.accessToken() ?: return false
        if (!ok(app.auth.requestText("POST", "/rest/v1/rpc/delete_my_account", "{}".toByteArray(), token), "delete_my_account")) return false
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("restored:$userId").apply()
        LocalStore(app).run { clearHealthData(); owner = null; cloudBackup = true }
        MedReminders.reschedule(app)
        app.auth.signOut()
        return true
    }

    enum class FreeUpResult { DONE, NOT_BACKED_UP }

    /** Deletes session files from this phone, only once every one of them is in the account. */
    suspend fun freeUpPhoneSpace(app: DaedalusApplication): FreeUpResult = mutex.withLock {
        if (!syncLocked(app)) return FreeUpResult.NOT_BACKED_UP
        val owner = owner(app) ?: return FreeUpResult.NOT_BACKED_UP
        val uploaded = uploadedSet(app, owner, KIND_SESSIONS)
        sessionFiles(app).filter { sessionKey(it) in uploaded }.forEach { it.delete() }
        return FreeUpResult.DONE
    }

    /** Deletes everything in the account (files, then rows) and turns backup off. Phone data stays. */
    suspend fun deleteUploaded(app: DaedalusApplication): Boolean = mutex.withLock { deleteUploadedLocked(app) }

    /** Deletes the account's data and this phone's health data, then signs out (guests: phone only). */
    suspend fun deleteEverything(app: DaedalusApplication): Boolean = mutex.withLock {
        val signedIn = owner(app) != null
        if (signedIn && !deleteUploadedLocked(app)) return false
        LocalStore(app).run { clearHealthData(); owner = null; cloudBackup = true }
        MedReminders.reschedule(app)
        if (signedIn) app.auth.signOut()
        return true
    }

    // --- Backup ---

    private suspend fun syncLocked(app: DaedalusApplication): Boolean {
        val owner = owner(app) ?: return false
        val store = LocalStore(app)
        if (!store.cloudBackup) return false
        val auth = app.auth
        if (store.owner == null || store.owner != auth.currentUserId()) return false
        // Bring the account's data back first, so this phone never overwrites it.
        if (!restoreLocked(app)) return false

        val profile = store.personalProfile(owner)
        if (profile != null && store.profileUploadPending()) {
            if (!auth.saveProfile(profile.toJson())) return false
            store.setProfileUploadPending(false)
        }

        // Dose times: send the phone's, and remove only those deleted on this phone.
        val schedule = store.schedule()
        if (schedule.isNotEmpty()) {
            val rows = JSONArray(schedule.map {
                JSONObject().put("id", it.id).put("name", it.name).put("dosage", it.dosage).put("hour", it.hour).put("minute", it.minute)
            })
            if (!upsert(app, "medication_schedule", rows)) return false
        }
        val deleted = store.deletedScheduleIds()
        if (deleted.isNotEmpty()) {
            val ids = deleted.joinToString(",")
            if (!ok(auth.requestText("DELETE", "/rest/v1/medication_schedule?id=in.($ids)", null, auth.accessToken()), "delete dose times")) return false
            store.clearDeletedScheduleIds(deleted)
        }

        // Dose logs and check-ins are only ever added, so just send the new ones.
        val scheduleIds = schedule.map { it.id }.toSet()
        val doneDoses = uploadedSet(app, owner, KIND_DOSES)
        val newDoses = store.medications().filter { it.id !in doneDoses }
        if (newDoses.isNotEmpty()) {
            val rows = JSONArray(newDoses.map {
                JSONObject().put("id", it.id).put("medication", it.name).put("dosage", it.dosage)
                    .put("taken_at", Instant.ofEpochMilli(it.timestamp).toString())
                    .put("schedule_id", it.slotId?.takeIf { id -> id in scheduleIds } ?: JSONObject.NULL)
            })
            if (!upsert(app, "dose_logs", rows)) return false
            markUploaded(app, owner, KIND_DOSES, newDoses.map { it.id })
        }

        val doneCheckIns = uploadedSet(app, owner, KIND_CHECKINS)
        val newCheckIns = store.checkIns().filter { it.id !in doneCheckIns }
        if (newCheckIns.isNotEmpty()) {
            val rows = JSONArray(newCheckIns.map {
                JSONObject().put("id", it.id).put("feeling", it.feeling.name.lowercase())
                    .put("recorded_at", Instant.ofEpochMilli(it.timestamp).toString())
            })
            if (!upsert(app, "checkins", rows)) return false
            markUploaded(app, owner, KIND_CHECKINS, newCheckIns.map { it.id })
        }

        val doneSessions = uploadedSet(app, owner, KIND_SESSIONS)
        val newSessions = sessionFiles(app).filter { sessionKey(it) !in doneSessions }.sortedBy { it.lastModified() }
        for (file in newSessions) {
            val name = file.nameWithoutExtension
            val startedAt = extractDate(name)?.let { Instant.ofEpochMilli(it).toString() }
            if (!auth.uploadSessionFile(file.name, file.readBytes(), extractMode(name), startedAt)) return false
            markUploaded(app, owner, KIND_SESSIONS, listOf(sessionKey(file)))
        }
        if (newSessions.isNotEmpty() || newDoses.isNotEmpty() || newCheckIns.isNotEmpty()) {
            Log.i(TAG, "Backed up ${newSessions.size} sessions, ${newDoses.size} doses, ${newCheckIns.size} check-ins")
        }
        return true
    }

    // --- Restore ---

    private suspend fun restoreLocked(app: DaedalusApplication): Boolean {
        val auth = app.auth
        val store = LocalStore(app)
        val userId = auth.currentUserId() ?: return false
        if (store.owner != userId) return false
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("restored:$userId", false)) return true
        val token = auth.accessToken() ?: return false
        suspend fun get(path: String): JSONArray? {
            val result = auth.requestText("GET", path, null, token)
            if (!ok(result, "restore $path")) return null
            return runCatching { JSONArray(result!!.second) }.getOrNull()
        }
        val schedule = get("/rest/v1/medication_schedule?select=*") ?: return false
        val doses = get("/rest/v1/dose_logs?select=*") ?: return false
        val checkIns = get("/rest/v1/checkins?select=*") ?: return false
        val sessions = get("/rest/v1/session_files?select=object_path") ?: return false

        fun millis(o: JSONObject, key: String) = runCatching { OffsetDateTime.parse(o.getString(key)).toInstant().toEpochMilli() }.getOrNull()
        store.mergeFromAccount(
            schedule = (0 until schedule.length()).map { schedule.getJSONObject(it) }.map {
                ScheduledDose(it.getString("id"), it.getString("name"), it.optString("dosage"), it.getInt("hour"), it.getInt("minute"),
                    millis(it, "created_at") ?: System.currentTimeMillis())
            },
            medications = (0 until doses.length()).map { doses.getJSONObject(it) }.mapNotNull { o ->
                millis(o, "taken_at")?.let { MedicationLog(o.getString("medication"), o.optString("dosage"), it, o.optString("schedule_id").takeUnless { o.isNull("schedule_id") || it.isBlank() }) }
            },
            checkIns = (0 until checkIns.length()).map { checkIns.getJSONObject(it) }.mapNotNull { o ->
                val feeling = runCatching { Feeling.valueOf(o.getString("feeling").uppercase()) }.getOrNull()
                val at = millis(o, "recorded_at")
                if (feeling != null && at != null) FeelingCheckIn(feeling, at) else null
            },
        )
        // What came from the account is already there.
        val email = owner(app)
        if (email != null) {
            markUploaded(app, email, KIND_DOSES, store.medications().map { it.id })
            markUploaded(app, email, KIND_CHECKINS, store.checkIns().map { it.id })
        }
        val dir = sessionsDir(app).apply { mkdirs() }
        for (i in 0 until sessions.length()) {
            val path = sessions.getJSONObject(i).getString("object_path")
            val name = File(path).name
            val target = File(dir, name)
            if (!target.exists()) {
                val file = auth.requestText("GET", "/storage/v1/object/sessions/${path.split('/').joinToString("/") { Uri.encode(it) }}", null, token)
                if (!ok(file, "restore $name")) return false
                File(dir, "$name.part").apply { writeText(file!!.second) }.renameTo(target)
            }
            if (email != null) markUploaded(app, email, KIND_SESSIONS, listOf(sessionKey(target)))
        }
        prefs.edit().putBoolean("restored:$userId", true).apply()
        MedReminders.reschedule(app)
        Log.i(TAG, "Restored ${schedule.length()} dose times, ${doses.length()} doses, ${checkIns.length()} check-ins, ${sessions.length()} sessions")
        return true
    }

    private suspend fun upsert(app: DaedalusApplication, table: String, rows: JSONArray): Boolean {
        val auth = app.auth
        val result = auth.requestText(
            "POST", "/rest/v1/$table?on_conflict=id", rows.toString().toByteArray(), auth.accessToken(),
            headers = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
        )
        if (!ok(result)) Log.w(TAG, "Backup of $table failed: $result")
        return ok(result)
    }

    // --- Deleting ---

    private suspend fun deleteUploadedLocked(app: DaedalusApplication): Boolean {
        val owner = owner(app) ?: return false
        val auth = app.auth
        val token = auth.accessToken() ?: return false
        val userId = auth.userId() ?: return false
        // Off before deleting, so a change made meanwhile isn't uploaded again.
        val store = LocalStore(app)
        val backupWasOn = store.cloudBackup
        store.cloudBackup = false
        if (!deleteRemote(app, token, userId)) {
            store.cloudBackup = backupWasOn
            return false
        }
        // Nothing is in the account now; keep it that way until backup is turned back on.
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            listOf(KIND_SESSIONS, KIND_DOSES, KIND_CHECKINS).forEach { remove("$it:$owner") }
        }.apply()
        store.setProfileUploadPending(true)
        return true
    }

    private suspend fun deleteRemote(app: DaedalusApplication, token: String, userId: String): Boolean {
        val auth = app.auth

        // Session files first: Storage only lets each user delete their own folder.
        var emptied = false
        var rounds = 0
        while (!emptied && rounds++ < MAX_LIST_ROUNDS) {
            val list = auth.requestText(
                "POST", "/storage/v1/object/list/sessions",
                JSONObject().put("prefix", userId).put("limit", 100).toString().toByteArray(), token,
            )
            if (!ok(list, "list session files")) return false
            val names = runCatching { JSONArray(list!!.second) }.getOrDefault(JSONArray())
                .let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
            emptied = names.isEmpty()
            for (name in names) {
                if (!ok(auth.requestText("DELETE", "/storage/v1/object/sessions/$userId/${Uri.encode(name)}", null, token), "delete $name")) return false
            }
        }
        if (!emptied) return false
        return ok(auth.requestText("POST", "/rest/v1/rpc/delete_my_data", "{}".toByteArray(), token), "delete_my_data")
    }

    // --- Helpers ---

    private fun owner(app: DaedalusApplication) = (app.auth.state.value as? AuthState.SignedIn)?.email

    /**
     * Upload marker for a session file: its name and size, so a copy that was later replaced by a
     * longer one (e.g. a retried transfer) is uploaded again.
     */
    private fun sessionKey(file: File) = "${file.name}|${file.length()}"

    private fun sessionFiles(app: DaedalusApplication): List<File> =
        sessionsDir(app).listFiles()?.filter { it.isFile && it.extension == "csv" }.orEmpty()

    private fun ok(result: Pair<Int, String>?, what: String? = null): Boolean {
        val success = result != null && result.first in 200..299
        if (!success && what != null) Log.w(TAG, "$what failed: ${result?.first} ${result?.second?.take(300)}")
        return success
    }

    private fun uploadedSet(app: DaedalusApplication, owner: String, kind: String): Set<String> {
        val json = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("$kind:$owner", "[]")
        val a = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }

    private fun markUploaded(app: DaedalusApplication, owner: String, kind: String, ids: List<String>) {
        val all = uploadedSet(app, owner, kind) + ids
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("$kind:$owner", JSONArray(all.toList()).toString()).apply()
    }

    private const val PREFS = "daedalus_uploads"
    /** Files are listed 100 at a time; this allows deleting up to 20,000. */
    private const val MAX_LIST_ROUNDS = 200
    // "uploaded_sessions" matches the key used by the first version, so files already sent aren't sent again.
    private const val KIND_SESSIONS = "uploaded_sessions"
    private const val KIND_DOSES = "uploaded_doses"
    private const val KIND_CHECKINS = "uploaded_checkins"
}
