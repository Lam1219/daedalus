package com.medicina.daedaluswatch.pilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.medicina.daedaluswatch.R
import com.medicina.daedaluswatch.sensor.StandWatchService
import com.medicina.daedaluswatch.sync.SessionSyncManager
import com.medicina.daedaluswatch.ui.MainActivity
import com.medicina.shared.pilot.AudioRoutes
import com.medicina.shared.pilot.BeatOutput
import com.medicina.shared.pilot.BeatRunner
import com.medicina.shared.pilot.CheckIn
import com.medicina.shared.pilot.MotionSamples
import com.medicina.shared.pilot.PendingPress
import com.medicina.shared.pilot.Pilot
import com.medicina.shared.pilot.PilotFileWriter
import com.medicina.shared.pilot.PressPlan
import com.medicina.shared.pilot.PressPlanner
import java.io.File
import java.time.LocalDate

private const val TAG = "PilotPresses"

/**
 * One "I'm stuck" press in study mode, from the draw to its file:
 * draw the place (B3) → save a pending file (round 001, #5) → play the beat there, falling back
 * to the wrist (BeatRunner) → at press + 60 s take the 90 s of motion from the ring (B4), holding a
 * wake lock until then (#4) → after the beat stops, the one-tap check-in (B5) → write the press
 * file (#14) and send it to the phone.
 */
object PilotPresses {
    /** B4: motion of the last 150 s, fed by StandWatchService while study mode is on. */
    val ring = com.medicina.shared.pilot.MotionRing()
    @Volatile var ringHasGyro = true

    class Press(
        val participant: String,
        val index: Long,
        val dayIndex: Long,
        val plan: PressPlan,
        val bpm: Int,
        val pressMs: Long,
        val runner: BeatRunner,
    ) {
        val id get() = "$participant-$index"
        @Volatile var beatStopMs: Long? = null
        @Volatile var checkin: CheckIn? = null
        @Volatile var checkinDone = false
        @Volatile var samples: MotionSamples? = null
        @Volatile var written = false
        var wakeLock: PowerManager.WakeLock? = null

        /** What is known now, for the pending file. */
        fun pending() = PendingPress(
            participant, index, dayIndex, plan.drawn, runner.player ?: plan.player, runner.placeUsed,
            plan.earbudsConnected, plan.phoneCarried, bpm, pressMs, runner.beatStartMs, runner.beatStartSource,
            beatStopMs, runner.fallbackReason, runner.fallbackAtMs,
        )
    }

    private val main = Handler(Looper.getMainLooper())
    /** All pilot file writes, one at a time and in order. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "PilotFiles") }
    /** Pending files of presses still alive in this process: not orphans. */
    private val live = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The press whose beat is playing now. */
    @Volatile var current: Press? = null
        private set
    /** The press whose check-in is on screen now. */
    @Volatile var checkinFor: Press? = null
        private set

    /** Where the current beat plays, for the small line on the walking screen. */
    val currentPlace get() = current?.runner?.player?.place

    /** Starts the beat of a press made at [pressMs]. Called by HapticService in study mode. */
    fun begin(c: Context, pressMs: Long, bpm: Int, output: BeatOutput): Press {
        val app = c.applicationContext
        checkinFor?.let { answer(c, null) } // a new press closes an open check-in, unanswered
        val settings = PilotStore.settings(c)
        val index = PilotStore.takePressIndex(c)
        val day = Pilot.dayIndex(settings.enrolment, LocalDate.now())
        val plan = PressPlanner.plan(
            settings, index, day,
            watchEarbuds = AudioRoutes.headphonesConnected(c),
            phoneEarbuds = PilotStore.phoneEarbuds,
            phoneCarried = PilotStore.phoneCarried(pressMs),
        )
        val runner = BeatRunner(output, schedule = { delay, block ->
            val r = Runnable(block); main.postDelayed(r, delay); { main.removeCallbacks(r) }
        })
        val press = Press(settings.participant, index, day, plan, bpm, pressMs, runner)
        current = press
        // #4: the capture at press + 60 s runs on a Handler, which stops while the watch sleeps;
        // a partial wake lock keeps it on time (released after the capture, or after 2 minutes).
        press.wakeLock = (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Daedalus:PilotCapture")
            .apply { setReferenceCounted(false); acquire(CAPTURE_WAKE_MS) }
        // #5: the press exists on disk before its beat starts.
        live += press.pending().fileName
        savePending(app, press)
        runner.onConfirmed = { savePending(app, press) }
        Log.i(TAG, "Press ${press.id}: day $day, drew ${plan.drawn} via ${plan.player} (earbuds=${plan.earbudsConnected}, carried=${plan.phoneCarried})")
        runner.start(plan, bpm)
        val wait = (pressMs + Pilot.BUFFER_AFTER_S * 1000L - System.currentTimeMillis()).coerceAtLeast(0)
        main.postDelayed({ capture(app, press) }, wait)
        return press
    }

    /** The beat stopped (Stop, the phone, or the 10-minute limit). Asks the check-in if today allows. */
    fun onBeatStopped(c: Context, nowMs: Long) {
        val p = current ?: return
        p.runner.stop() // while [current] is set, so the phone gets its stop message
        current = null
        p.beatStopMs = nowMs
        savePending(c.applicationContext, p)
        val today = LocalDate.now()
        val day = PilotStore.checkinDay(c)
        if (day.shouldAsk(today)) {
            PilotStore.setCheckinDay(c, day.afterAsking(today))
            checkinFor = p
            main.postDelayed({ if (checkinFor === p) answer(c, null) }, CHECKIN_TIMEOUT_MS)
            if (!MainActivity.isVisible) showCheckinOverWatchFace(c)
        } else {
            p.checkinDone = true
            maybeWrite(c.applicationContext, p)
        }
    }

    /** One tap on the check-in, or null when it closed without an answer. */
    fun answer(c: Context, answer: CheckIn?) {
        val p = checkinFor ?: return
        checkinFor = null
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_CHECKIN)
        p.checkin = answer
        p.checkinDone = true
        maybeWrite(c.applicationContext, p)
    }

    private fun capture(c: Context, p: Press) {
        // Batched samples may still sit in the sensor hub: flush them, then read the ring.
        StandWatchService.flushSensors()
        main.postDelayed({
            p.samples = ring.snapshot(p.pressMs - Pilot.BUFFER_BEFORE_S * 1000L, p.pressMs + Pilot.BUFFER_AFTER_S * 1000L, ringHasGyro)
            Log.i(TAG, "Press ${p.id}: ${p.samples?.size} motion samples")
            p.wakeLock?.let { if (it.isHeld) it.release() }
            p.wakeLock = null
            maybeWrite(c, p)
        }, FLUSH_WAIT_MS)
    }

    @Synchronized
    private fun maybeWrite(c: Context, p: Press) {
        val samples = p.samples ?: return
        if (!p.checkinDone || p.written || current === p) return
        p.written = true
        val pending = p.pending()
        val record = pending.toRecord(samples, p.checkin)
        writeInBackground(c, record.fileName, record.toJson(), pending.fileName)
    }

    /** #14: never crashes the app; a failed write leaves the pending file for the next start. */
    private fun writeInBackground(c: Context, name: String, json: String, pendingName: String) {
        io.execute {
            runCatching {
                val dir = pilotDir(c)
                val f = PilotFileWriter.write(dir, name, json) { Log.w(TAG, it) }
                if (f != null) {
                    File(dir, pendingName).delete()
                    live -= pendingName
                    Log.i(TAG, "Wrote ${f.name}")
                    SessionSyncManager(c).syncPilotFiles()
                } else {
                    Log.e(TAG, "Couldn't write $name; its pending file stays for the next start")
                }
            }.onFailure { Log.e(TAG, "Press file $name: ${it.message}") }
        }
    }

    private fun savePending(c: Context, p: Press) {
        val pending = p.pending()
        io.execute {
            // In order with the final write: a pending save queued before it never outlives it.
            if (!p.written) runCatching { PilotFileWriter.write(pilotDir(c), pending.fileName, pending.encode(), replace = true) { Log.w(TAG, it) } }
        }
    }

    /**
     * #5: finishes presses left pending by a crash, a kill or a reboot, with whatever motion the
     * ring still holds for them (usually none after a restart) and no check-in.
     */
    fun recoverOrphans(c: Context) {
        val app = c.applicationContext
        io.execute {
            runCatching {
                val dir = pilotDir(app)
                val orphans = dir.listFiles()?.filter { it.name.startsWith(PendingPress.PREFIX) && !it.name.endsWith(".part") && it.name !in live }.orEmpty()
                for (f in orphans) {
                    val pending = PendingPress.decode(f.readText())
                    if (pending == null) { Log.w(TAG, "Unreadable ${f.name}, kept"); continue }
                    val samples = ring.snapshot(pending.pressMs - Pilot.BUFFER_BEFORE_S * 1000L, pending.pressMs + Pilot.BUFFER_AFTER_S * 1000L, ringHasGyro)
                    val record = pending.toRecord(samples, null, recovered = true)
                    // Already finished (crash between the write and the delete): just tidy up.
                    if (File(dir, record.fileName).exists()) { f.delete(); continue }
                    if (PilotFileWriter.write(dir, record.fileName, record.toJson()) { Log.w(TAG, it) } != null) {
                        f.delete()
                        Log.i(TAG, "Recovered ${record.fileName} (${samples.size} samples)")
                    }
                }
                if (orphans.isNotEmpty()) SessionSyncManager(app).syncPilotFiles()
            }.onFailure { Log.e(TAG, "Recovering pending presses: ${it.message}") }
        }
    }

    /** Over the watch face when the beat stopped on its own (screen off). */
    private fun showCheckinOverWatchFace(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CHECKIN, c.getString(R.string.pilot_checkin_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            NOTIFICATION_CHECKIN,
            Notification.Builder(c, CHANNEL_CHECKIN)
                .setContentTitle(c.getString(R.string.pilot_checkin_title))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .setAutoCancel(true)
                .setTimeoutAfter(CHECKIN_TIMEOUT_MS)
                .build(),
        )
    }

    fun pilotDir(c: Context) = File(c.filesDir, "pilot")

    /** The check-in closes by itself after this long, saved as no answer. */
    const val CHECKIN_TIMEOUT_MS = 30_000L
    private const val FLUSH_WAIT_MS = 1_500L
    /** Press + 60 s + flush, with margin; the lock is released as soon as the capture is done. */
    private const val CAPTURE_WAKE_MS = 120_000L
    private const val CHANNEL_CHECKIN = "daedalus_pilot_checkin"
    private const val NOTIFICATION_CHECKIN = 31
}
