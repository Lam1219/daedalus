package com.medicina.daedaluswatch.pilot

import android.content.Context
import java.io.File

/**
 * Round 004, #4: a cheap check, kept apart from PilotPresses so asking doesn't create it (its
 * motion ring and file queue). No pilot folder means this watch has never had a pilot press.
 */
fun pilotFolderExists(c: Context): Boolean = pilotFolderExists(c.filesDir)

fun pilotFolderExists(filesDir: File): Boolean = File(filesDir, "pilot").isDirectory

/**
 * Round 005, #6: set by PilotPresses.begin, read by the watch screen every frame (no preferences
 * read). A press that starts while the screen's own flag is stale still gets its check-in.
 */
object PilotFlags {
    @Volatile var pressStarted = false

    /** The screen looks at PilotPresses when study mode or pilot files were seen, or a press began. */
    fun screenReadsPresses(pilotOn: Boolean) = pilotOn || pressStarted
}
