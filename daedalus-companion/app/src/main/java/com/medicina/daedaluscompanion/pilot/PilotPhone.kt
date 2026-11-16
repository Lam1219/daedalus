package com.medicina.daedaluscompanion.pilot

import android.content.Context
import com.medicina.shared.pilot.PilotSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The phone's own rules for the home pilot.
 *
 * What is guarded against mixed builds:
 * - The phone never plays earbud audio for a pilot press: Android earbuds are cut from the pilot
 *   (owner, round 008), so it refuses PHONE_AUDIO at once and the watch's wrist takes over.
 * - Any watch build from before round 006 ends up with study mode off. With study on the phone
 *   sends an "off" first and then "2|…", which those builds reject (round 007, #1). Such a watch
 *   never runs pilot presses, whatever it had stored.
 * What isn't guarded:
 * - A watch on a current build but an older phone. The phone's "1|…" is accepted, and its earbuds
 *   value counts as not checked, so earbuds are never drawn.
 * - A watch that misses both messages (not connected). It keeps its last settings until the phone
 *   resends them; the install check (study off, then on, watched on the watch) catches it.
 */
object PilotPhoneRules {
    /**
     * Round 006 (A): the code and seed as typed, saved only when both are valid. Null means "don't
     * save" (half-typed code, empty or out-of-range seed).
     */
    fun confirmIdentity(saved: com.medicina.shared.pilot.PilotSettings, code: String, seedText: String): com.medicina.shared.pilot.PilotSettings? {
        val seed = seedText.toLongOrNull() ?: return null
        if (!com.medicina.shared.pilot.PilotSettings.isValidCode(code) || !com.medicina.shared.pilot.PilotSettings.isValidSeed(seed)) return null
        return saved.copy(participant = code, seed = seed)
    }

    /** Round 007, #5: the typed code and seed differ from the saved ones. The seed compares as a number ("042" is 42). */
    fun isUnsaved(saved: com.medicina.shared.pilot.PilotSettings, code: String, seedText: String): Boolean =
        code != saved.participant || seedText.toLongOrNull() != saved.seed

    /** "Delete pilot files" deletes this phone's files only, and only while study mode is off (round 008). */
    fun canDeletePilotFiles(settings: com.medicina.shared.pilot.PilotSettings) = !settings.enabled

    /**
     * Round 006, #6: why the phone won't start [player] for a pilot press, or null to play.
     * Players that aren't the phone's keep "not_a_phone_player".
     */
    fun refusal(player: com.medicina.shared.pilot.Player, settings: com.medicina.shared.pilot.PilotSettings): String? = when (player) {
        com.medicina.shared.pilot.Player.PHONE_VIBRATION -> null
        com.medicina.shared.pilot.Player.PHONE_AUDIO -> "earbuds_off_in_pilot" // round 008: never on Android
        else -> "not_a_phone_player"
    }

    /** Earbuds as reported to the watch: never, Android earbuds are cut from the pilot (round 008). */
    @Suppress("UNUSED_PARAMETER")
    fun earbudsForStatus(settings: com.medicina.shared.pilot.PilotSettings, headphonesConnected: Boolean) =
        com.medicina.shared.pilot.PressPlanner.ANDROID_EARBUDS_IN_PILOT && headphonesConnected

    /** Whether the phone may start [player] for a pilot press: only the pocket vibration. */
    fun mayPlay(player: com.medicina.shared.pilot.Player, settings: com.medicina.shared.pilot.PilotSettings) = refusal(player, settings) == null
}

/** The phone's copy of the pilot settings (Profile → Home pilot). Sent to the watch on every change. */
object PilotPrefs {
    private const val PREFS = "pilot"
    private const val KEY = "settings"

    fun settings(c: Context): PilotSettings =
        fromStored(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    /** A stored earbuds check (rounds 006-007) is cleared on load (round 009, #6). */
    fun fromStored(text: String?): PilotSettings =
        (text?.let { PilotSettings.decode(it) } ?: PilotSettings()).copy(earbudsCheckedFor = "")

    fun save(c: Context, s: PilotSettings) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, s.encode()).apply()
    }
}

/**
 * Press and battery files from the watch, kept on the phone only (never uploaded, never in the
 * session list or the cloud backup), until "Export pilot files" hands them over.
 */
object PilotFiles {
    fun dir(c: Context) = File(c.filesDir, "pilot")

    fun list(c: Context): List<File> = dir(c).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedBy { it.name }.orEmpty()

    fun pressCount(c: Context) = list(c).count { it.name.startsWith("pilot_") }

    /** Saves a file from the watch (written to a temporary file, then renamed). */
    @Synchronized
    fun save(c: Context, name: String, data: ByteArray) {
        val safe = File(name).name
        require(safe.startsWith("pilot_") || safe.startsWith("battery_")) { "not a pilot file: $safe" }
        val d = dir(c).apply { mkdirs() }
        val target = File(d, safe)
        if (target.exists() && target.length() == data.size.toLong() && target.readBytes().contentEquals(data)) return
        val tmp = File(d, "$safe.part")
        tmp.writeBytes(data)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    /** One zip of every pilot file, in the app's cache (shared through the FileProvider). */
    fun zip(c: Context, participant: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val out = File(c.cacheDir, "daedalus_pilot_${participant.ifBlank { "unknown" }}_$stamp.zip")
        c.cacheDir.listFiles()?.filter { it.name.startsWith("daedalus_pilot_") && it.name.endsWith(".zip") }?.forEach { it.delete() }
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            for (f in list(c)) {
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return out
    }

    fun deleteAll(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
    }
}

/**
 * "The phone was carried in the last minute": its step counter counted a step. Event times are
 * the sensor's (nanoseconds since boot), so batched steps keep their real times.
 */
class StepTracker {
    private var lastCount: Float? = null
    /** Boot-time ms of the last counted step, or null. */
    var lastStepMs: Long? = null
        private set

    fun onCounter(eventMs: Long, count: Float) {
        val before = lastCount
        lastCount = count
        // The first event after registering only reports the running total.
        if (before != null && count > before) lastStepMs = eventMs
    }

    /** One step detector event = one step. */
    fun onStep(eventMs: Long) {
        if (lastStepMs == null || eventMs > lastStepMs!!) lastStepMs = eventMs
    }

    fun agoMs(nowMs: Long): Long? = lastStepMs?.let { (nowMs - it).coerceAtLeast(0) }
}

/**
 * "The phone moved in the last minute" (B10): the accelerometer, at a low rate, changed by more
 * than [thresholdMs2] on some axis. A phone on a table stays within a few hundredths; standing up
 * with it in a trouser pocket tilts it by tens of degrees. The threshold is a first guess, to
 * check on a real phone (handoff).
 */
class MovementWindow(private val windowMs: Long = 60_000, private val thresholdMs2: Float = 1.5f) {
    private val t = ArrayDeque<Long>()
    private val xyz = ArrayDeque<FloatArray>()

    @Synchronized
    fun add(eventMs: Long, x: Float, y: Float, z: Float) {
        t.addLast(eventMs); xyz.addLast(floatArrayOf(x, y, z))
        while (t.isNotEmpty() && t.first() < eventMs - windowMs) { t.removeFirst(); xyz.removeFirst() }
    }

    /** Null when there are no samples from the last minute to judge by. */
    @Synchronized
    fun moved(nowMs: Long): Boolean? {
        val recent = t.indices.filter { t.elementAt(it) >= nowMs - windowMs }.map { xyz.elementAt(it) }
        if (recent.size < 2) return null
        return (0..2).any { a -> recent.maxOf { it[a] } - recent.minOf { it[a] } > thresholdMs2 }
    }
}
