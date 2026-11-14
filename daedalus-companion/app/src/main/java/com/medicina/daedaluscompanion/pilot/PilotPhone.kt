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
 * What is guarded against mixed builds (round 005 #5, round 006 #5):
 * - the phone never plays earbud audio unless this participant's earbuds check is recorded here;
 * - a watch build from before round 004 rejects the settings (study on is sent as "2"), so it
 *   never runs pilot presses at all.
 * What isn't: a watch from round 004 or 005 with its own earbuds connected; those builds also
 * reject "2", so they never enter study mode either. The handoff's install check is the backstop.
 */
object PilotPhoneRules {
    /**
     * Round 006 (A): the code and seed as typed, saved only when both are valid. Null means "don't
     * save" (half-typed code, empty or out-of-range seed). The earbuds check stays tied to the code
     * and seed it was done for, so a new identity simply isn't checked.
     */
    fun confirmIdentity(saved: com.medicina.shared.pilot.PilotSettings, code: String, seedText: String): com.medicina.shared.pilot.PilotSettings? {
        val seed = seedText.toLongOrNull() ?: return null
        if (!com.medicina.shared.pilot.PilotSettings.isValidCode(code) || !com.medicina.shared.pilot.PilotSettings.isValidSeed(seed)) return null
        return saved.copy(participant = code, seed = seed)
    }

    /** The plain status line on the pilot screen: "Earbuds checked for P05" or "Earbuds not checked". */
    inline fun earbudsStatusLine(settings: com.medicina.shared.pilot.PilotSettings, checked: (String) -> String, notChecked: () -> String) =
        if (settings.earbudsChecked) checked(settings.participant) else notChecked()

    /**
     * Round 006, #6: why the phone won't start [player] for a pilot press, or null to play.
     * Players that aren't the phone's keep "not_a_phone_player".
     */
    fun refusal(player: com.medicina.shared.pilot.Player, settings: com.medicina.shared.pilot.PilotSettings): String? = when (player) {
        com.medicina.shared.pilot.Player.PHONE_VIBRATION -> null
        com.medicina.shared.pilot.Player.PHONE_AUDIO -> if (settings.earbudsChecked) null else "earbuds_not_checked"
        else -> "not_a_phone_player"
    }

    /** Earbuds as reported to the watch: only once this participant's earbuds check passed. */
    fun earbudsForStatus(settings: com.medicina.shared.pilot.PilotSettings, headphonesConnected: Boolean) =
        settings.earbudsChecked && headphonesConnected

    /** Whether the phone may start [player] for a pilot press. PHONE_AUDIO needs the earbuds check. */
    fun mayPlay(player: com.medicina.shared.pilot.Player, settings: com.medicina.shared.pilot.PilotSettings) = refusal(player, settings) == null
}

/** The phone's copy of the pilot settings (Profile → Home pilot). Sent to the watch on every change. */
object PilotPrefs {
    private const val PREFS = "pilot"
    private const val KEY = "settings"

    fun settings(c: Context): PilotSettings =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { PilotSettings.decode(it) } ?: PilotSettings()

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
