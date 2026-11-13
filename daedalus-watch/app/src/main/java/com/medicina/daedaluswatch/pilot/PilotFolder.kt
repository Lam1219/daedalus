package com.medicina.daedaluswatch.pilot

import android.content.Context
import java.io.File

/**
 * Round 004, #4: a cheap check, kept apart from PilotPresses so asking doesn't create it (its
 * motion ring and file queue). No pilot folder means this watch has never had a pilot press.
 */
fun pilotFolderExists(c: Context): Boolean = pilotFolderExists(c.filesDir)

fun pilotFolderExists(filesDir: File): Boolean = File(filesDir, "pilot").isDirectory
