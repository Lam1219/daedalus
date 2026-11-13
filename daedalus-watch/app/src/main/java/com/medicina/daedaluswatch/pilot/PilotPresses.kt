package com.medicina.daedaluswatch.pilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
import com.medicina.shared.pilot.Pilot
import com.medicina.shared.pilot.Place
import com.medicina.shared.pilot.PressPlan
import com.medicina.shared.pilot.PressPlanner
import com.medicina.shared.pilot.PressRecord
import java.io.File
import java.time.LocalDate

private const val TAG = "PilotPresses"

/**
 * One "I'm stuck" press in study mode, from the draw to its file:
 * draw the place (B3) → play the beat there, falling back to the wrist (BeatRunner) →
 * at press + 60 s take the 90 s of motion from the ring (B4) → after the beat stops, the one-tap
 * check-in (B5) → write the press file and send it to the phone.
 */
object PilotPresses {
    /** B4: motion of the last two minutes, fed by StandWatchService while study mode is on. */
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
    }

    private val main = Handler(Looper.getMainLooper())

    /** The press whose beat is playing now. */
    @Volatile var current: Press? = null
        private set
    /** The press whose check-in is on screen now. */
    @Volatile var checkinFor: Press? = null
        private set

    /** Where the current beat plays, for the small line on the walking screen. */
    val currentPlace: Place? get() = current?.runner?.player?.place

    /** Starts the beat of a press made at [pressMs]. Called by HapticService in study mode. */
    fun begin(c: Context, pressMs: Long, bpm: Int, output: BeatOutput): Press {
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
        Log.i(TAG, "Press ${press.id}: day $day, drew ${plan.drawn} via ${plan.player} (earbuds=${plan.earbudsConnected}, carried=${plan.phoneCarried})")
        runner.start(plan, bpm)
        // Keep the motion until 60 s after the press, then take it from the ring.
        val wait = (pressMs + Pilot.BUFFER_AFTER_S * 1000L - System.currentTimeMillis()).coerceAtLeast(0)
        main.postDelayed({ capture(c.applicationContext, press) }, wait)
        return press
    }

    /** The beat stopped (Stop, the phone, or the 10-minute limit). Asks the check-in if today allows. */
    fun onBeatStopped(c: Context, nowMs: Long) {
        val p = current ?: return
        p.runner.stop() // while [current] is set, so the phone gets its stop message
        current = null
        p.beatStopMs = nowMs
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
            maybeWrite(c, p)
        }, FLUSH_WAIT_MS)
    }

    @Synchronized
    private fun maybeWrite(c: Context, p: Press) {
        val samples = p.samples ?: return
        if (!p.checkinDone || p.written || current === p) return
        p.written = true
        val r = p.runner
        val record = PressRecord(
            participant = p.participant, pressIndex = p.index, dayIndex = p.dayIndex,
            place = r.placeUsed ?: Place.WRIST,
            earbudsConnected = p.plan.earbudsConnected, phoneCarried = p.plan.phoneCarried,
            paceSpm = p.bpm, pressMs = p.pressMs,
            beatStartMs = r.beatStartMs ?: p.pressMs,
            beatStopMs = p.beatStopMs, checkin = p.checkin, samples = samples,
            placeDrawn = p.plan.drawn, player = r.player ?: com.medicina.shared.pilot.Player.WATCH_HAPTIC,
            beatStartSource = r.beatStartSource ?: "missing",
            fallbackReason = r.fallbackReason, fallbackAtMs = r.fallbackAtMs,
        )
        Thread {
            val dir = pilotDir(c).apply { mkdirs() }
            // pilot_analysis can't read a press without motion; keep it, out of the analysis glob.
            val name = if (samples.size >= 2) record.fileName else record.fileName + ".nomotion"
            val tmp = File(dir, "$name.part")
            tmp.writeText(record.toJson())
            tmp.renameTo(File(dir, name))
            Log.i(TAG, "Wrote $name")
            SessionSyncManager(c).syncPilotFiles()
        }.apply { name = "PressFile" }.start()
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
    private const val CHANNEL_CHECKIN = "daedalus_pilot_checkin"
    private const val NOTIFICATION_CHECKIN = 31
}
