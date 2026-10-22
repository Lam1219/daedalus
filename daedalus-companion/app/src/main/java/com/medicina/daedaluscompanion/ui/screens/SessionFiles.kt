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

/** Single canonical location session files live in — must match WatchDataListener.kt's saveSessionFile(). */
fun sessionsDir(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "daedalus/sessions")

/**
 * Directories session files used to be written to redundantly, before saveSessionFile() was
 * consolidated to write only to sessionsDir(). Swept into sessionsDir() on load so files from
 * earlier builds aren't orphaned (invisible to the list, and skipped by "delete all").
 */
private fun legacySessionDirs(context: Context): List<File> = listOf(
    File(context.getExternalFilesDir(null), "sessions"),
    File(context.filesDir, "daedalus/sessions"),
    File(context.filesDir, "sessions"),
    File(context.filesDir.parent, "files/sessions"),
    File(android.os.Environment.getExternalStorageDirectory(), "Daedalus/sessions"),
)

private fun migrateLegacySessionFiles(context: Context) {
    val target = sessionsDir(context)
    if (!target.exists()) target.mkdirs()

    for (dir in legacySessionDirs(context)) {
        if (!dir.exists()) continue
        dir.listFiles()?.filter { it.isFile }?.forEach { file ->
            val destination = File(target, file.name)
            if (!destination.exists()) {
                file.copyTo(destination, overwrite = false)
            }
            file.delete()
        }
    }
}

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
