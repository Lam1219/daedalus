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

private const val TAG = "CloudBackup"

/**
 * Backs up the phone's health data to the signed-in account in Supabase, and deletes it.
 *
 * - Backup: profile, medication schedule, dose logs, check-ins and watch session files. Runs at
 *   start, after each change and when a session arrives; whatever fails (offline) is retried later.
 *   Guests and people who turned backup off keep everything on the phone only.
 * - Deleting: free up space (phone copies of backed-up sessions), delete uploaded data
 *   (account only, turns backup off), or delete everything (phone + account, then sign out).
 */
object CloudBackup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun syncAsync(context: Context) {
        val app = context.applicationContext as? DaedalusApplication ?: return
        scope.launch { sync(app) }
    }

    /** Backs up everything not yet in the account. True when it's all there. */
    suspend fun sync(app: DaedalusApplication): Boolean = mutex.withLock { syncLocked(app) }

    enum class FreeUpResult { DONE, NOT_BACKED_UP }

    /** Deletes session files from this phone, only once every one of them is in the account. */
    suspend fun freeUpPhoneSpace(app: DaedalusApplication): FreeUpResult = mutex.withLock {
        if (!syncLocked(app)) return FreeUpResult.NOT_BACKED_UP
        val owner = owner(app) ?: return FreeUpResult.NOT_BACKED_UP
        val uploaded = uploadedSet(app, owner, KIND_SESSIONS)
        sessionFiles(app).filter { it.name in uploaded }.forEach { it.delete() }
        return FreeUpResult.DONE
    }

    /** Deletes everything in the account (files, then rows) and turns backup off. Phone data stays. */
    suspend fun deleteUploaded(app: DaedalusApplication): Boolean = mutex.withLock { deleteUploadedLocked(app) }

    /** Deletes the account's data and this phone's health data, then signs out (guests: phone only). */
    suspend fun deleteEverything(app: DaedalusApplication): Boolean = mutex.withLock {
        val signedIn = owner(app) != null
        if (signedIn && !deleteUploadedLocked(app)) return false
        sessionFiles(app).forEach { it.delete() }
        LocalStore(app).run { clearHealthData(); cloudBackup = true }
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

        val profile = store.personalProfile(owner)
        if (profile != null && store.profileUploadPending()) {
            if (!auth.saveProfile(profile.toJson())) return false
            store.setProfileUploadPending(false)
        }

        // The schedule mirrors the phone: removed dose times are removed from the account too.
        val schedule = store.schedule()
        if (schedule.isNotEmpty()) {
            val rows = JSONArray(schedule.map {
                JSONObject().put("id", it.id).put("name", it.name).put("dosage", it.dosage).put("hour", it.hour).put("minute", it.minute)
            })
            if (!upsert(app, "medication_schedule", rows)) return false
        }
        val keep = schedule.joinToString(",") { it.id }
        val filter = if (keep.isEmpty()) "user_id=not.is.null" else "id=not.in.($keep)"
        if (!ok(auth.requestText("DELETE", "/rest/v1/medication_schedule?$filter", null, auth.accessToken()))) return false

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
        val newSessions = sessionFiles(app).filter { it.name !in doneSessions }.sortedBy { it.lastModified() }
        for (file in newSessions) {
            val name = file.nameWithoutExtension
            val startedAt = extractDate(name)?.let { Instant.ofEpochMilli(it).toString() }
            if (!auth.uploadSessionFile(file.name, file.readBytes(), extractMode(name), startedAt)) return false
            markUploaded(app, owner, KIND_SESSIONS, listOf(file.name))
        }
        if (newSessions.isNotEmpty() || newDoses.isNotEmpty() || newCheckIns.isNotEmpty()) {
            Log.i(TAG, "Backed up ${newSessions.size} sessions, ${newDoses.size} doses, ${newCheckIns.size} check-ins")
        }
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
        if (!ok(auth.requestText("POST", "/rest/v1/rpc/delete_my_data", "{}".toByteArray(), token), "delete_my_data")) return false

        // Nothing is in the account now; keep it that way until backup is turned back on.
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            listOf(KIND_SESSIONS, KIND_DOSES, KIND_CHECKINS).forEach { remove("$it:$owner") }
        }.apply()
        LocalStore(app).run { cloudBackup = false; setProfileUploadPending(true) }
        return true
    }

    // --- Helpers ---

    private fun owner(app: DaedalusApplication) = (app.auth.state.value as? AuthState.SignedIn)?.email

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
