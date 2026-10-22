package com.medicina.daedaluscompanion.ui.screens

import android.content.Context
import android.content.Intent
import com.medicina.daedaluscompanion.R
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionItem(
    val fileName: String,
    val mode: String,
    val dateTimeLabel: String,
    val date: Long,
    val size: Long,
)

/**
 * Single canonical location session files live in. App-internal storage, so other apps and a USB
 * connection can't read them. WatchDataListener, SessionState and CloudBackup all use this.
 */
fun sessionsDir(context: Context): File = File(context.filesDir, "daedalus/sessions")

/**
 * Directories session files were written to by earlier builds, including external storage
 * (readable over USB). Moved into sessionsDir() once, so none are orphaned or left exposed.
 */
private fun legacySessionDirs(context: Context): List<File> = listOfNotNull(
    context.getExternalFilesDir(null)?.let { File(it, "daedalus/sessions") },
    context.getExternalFilesDir(null)?.let { File(it, "sessions") },
    File(context.filesDir, "sessions"),
    File(android.os.Environment.getExternalStorageDirectory(), "Daedalus/sessions"),
)

private val migrationLock = Any()
@Volatile private var migrationChecked = false

/**
 * Moves session files from [legacySessionDirs] into [sessionsDir]. Runs once per install (a flag
 * is kept once nothing is left to move); cheap to call again. Copies to a temporary file and
 * renames, so an interrupted move never leaves a cut-off copy.
 */
fun migrateLegacySessionFiles(context: Context) {
    if (migrationChecked) return
    synchronized(migrationLock) {
        if (migrationChecked) return
        val prefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED_INTERNAL, false)) { migrationChecked = true; return }
        val target = sessionsDir(context).apply { mkdirs() }
        var allMoved = true
        for (dir in legacySessionDirs(context)) {
            dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.forEach { file ->
                runCatching {
                    val destination = File(target, file.name)
                    // Keep the longer copy if both exist (a retried transfer can be the complete one).
                    if (!destination.exists() || file.length() > destination.length()) {
                        val temp = File(target, "${file.name}.part")
                        file.copyTo(temp, overwrite = true)
                        destination.delete()
                        if (!temp.renameTo(destination)) error("rename failed")
                    }
                    file.delete()
                }.onFailure { allMoved = false }
            }
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }?.forEach { it.delete() }
        }
        if (allMoved) prefs.edit().putBoolean(KEY_MIGRATED_INTERNAL, true).apply()
        migrationChecked = allMoved
    }
}

private const val MIGRATION_PREFS = "session_files"
private const val KEY_MIGRATED_INTERNAL = "migrated_to_internal"

fun loadSessions(context: Context): List<SessionItem> {
    migrateLegacySessionFiles(context)

    val files = sessionsDir(context).listFiles()
        ?.filter { it.isFile && (it.extension == "csv" || it.name.contains("session")) }
        ?: emptyList()

    return files.map { file ->
        val name = file.nameWithoutExtension
        val mode = extractMode(name)
        val date = extractDate(name) ?: file.lastModified()
        SessionItem(
            fileName = file.name,
            mode = mode,
            dateTimeLabel = formatSessionDateTime(date),
            date = date,
            size = file.length(),
        )
    }.sortedByDescending { it.date }
}

/** Groups sessions by mode, ordered to match the mode picker, with unrecognized modes last. */
fun groupSessionsByMode(sessions: List<SessionItem>): List<Pair<HapticMode?, List<SessionItem>>> {
    val byMode = sessions.groupBy { it.mode }
    val known = HAPTIC_MODES.mapNotNull { mode -> byMode[mode.id]?.let { mode to it } }
    val unknownIds = byMode.keys.filter { id -> HAPTIC_MODES.none { it.id == id } }
    val unknown = unknownIds.map { id -> null to byMode.getValue(id) }
    return known + unknown
}

fun extractMode(fileName: String): String {
    val parts = fileName.split("_")
    return if (parts.size >= 2) parts[1] else "unknown"
}

fun extractDate(fileName: String): Long? {
    val regex = """session_\w+_(\d{8}_\d{6})""".toRegex()
    val match = regex.find(fileName)
    return match?.groupValues?.get(1)?.let { dateStr ->
        try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(dateStr)?.time
        } catch (e: Exception) {
            null
        }
    }
}

/** Date/time label used to distinguish sessions within the same mode group, in the device language. */
fun formatSessionDateTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

fun downloadSession(context: Context, session: SessionItem) {
    val sourceFile = File(sessionsDir(context), session.fileName)
    if (!sourceFile.exists()) return

    // Use the system share intent to save/open the file
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        sourceFile
    )

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.sessions_download_chooser)))
}

fun deleteAllSessions(context: Context, sessions: List<SessionItem>) {
    val dir = sessionsDir(context)
    for (session in sessions) {
        File(dir, session.fileName).delete()
    }
}
