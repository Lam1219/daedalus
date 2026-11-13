package com.medicina.daedaluswatch.pilot

import com.medicina.daedaluswatch.sensor.WatchBootReceiver
import com.medicina.shared.pilot.BeatOutput
import com.medicina.shared.pilot.BeatRunner
import com.medicina.shared.pilot.PilotFileWriter
import com.medicina.shared.pilot.PilotSettings
import com.medicina.shared.pilot.Place
import com.medicina.shared.pilot.Player
import com.medicina.shared.pilot.PressPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Review round 002 (Android). Each test names its finding. */
class RoundTwoFixesTest {

    /** An audio-like player: confirms when told, and reports its measured start later. */
    private class Audio : BeatOutput {
        val started = mutableListOf<Player>()
        val callbacks = HashMap<Player, (Long?, String) -> Unit>()
        override fun start(player: Player, bpm: Int, onStarted: (Long?, String) -> Unit, onFailed: (String) -> Unit) {
            started += player
            callbacks[player] = onStarted
            if (player == Player.WATCH_HAPTIC) onStarted(9_000, "watch_motor")
        }
        override fun stop(player: Player) {}
    }

    private class Clock {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        var now = 0L
        fun schedule(d: Long, b: () -> Unit): () -> Unit { val t = (now + d) to b; tasks += t; return { tasks.remove(t) } }
        fun advance(ms: Long) { now += ms; tasks.filter { it.first <= now }.forEach { tasks.remove(it); it.second() } }
    }

    private val base = PressPlanner.plan(PilotSettings(true, "P01", 1, 0), 0, 30, false, false, false)
    private fun plan(p: Player) = base.copy(drawn = p.place, player = p)

    // ---- #8: 1 s for every player; confirmation on playing; the measured start may come later

    @Test
    fun n8_watchAudioThatNeverPlaysFallsBackAfterOneSecond() {
        val clock = Clock(); val out = Audio()
        val r = BeatRunner(out, clock::schedule)
        r.start(plan(Player.WATCH_AUDIO), 100)
        clock.advance(999)
        assertEquals(listOf(Player.WATCH_AUDIO), out.started)
        clock.advance(1)
        assertEquals("wrist at 1 s, as for the phone", listOf(Player.WATCH_AUDIO, Player.WATCH_HAPTIC), out.started)
        assertEquals(Place.WRIST, r.placeUsed)
    }

    @Test
    fun n8_confirmationWithoutATimeStopsTheSafetyNetAndTheMeasuredStartIsRecordedLater() {
        val clock = Clock(); val out = Audio()
        val r = BeatRunner(out, clock::schedule)
        r.start(plan(Player.WATCH_AUDIO), 100)
        clock.advance(200)
        out.callbacks.getValue(Player.WATCH_AUDIO)(null, "audio_written_bt") // playing, no timestamp yet
        assertTrue(r.confirmed)
        assertNull("no start time yet", r.beatStartMs)
        clock.advance(5_000)
        assertEquals("no fallback once playing", listOf(Player.WATCH_AUDIO), out.started)
        assertEquals(Place.EARBUDS, r.placeUsed)
        // The output timestamp arrives 1.6 s after the press, after the 1 s mark: still recorded.
        out.callbacks.getValue(Player.WATCH_AUDIO)(1_234, "audio_timestamp_bt")
        assertEquals(1_234L, r.beatStartMs)
        assertEquals("audio_timestamp_bt", r.beatStartSource)
        // A later report changes nothing.
        out.callbacks.getValue(Player.WATCH_AUDIO)(9_999, "audio_timestamp_bt")
        assertEquals(1_234L, r.beatStartMs)
    }

    @Test
    fun n8_noTimestampMeansNullStartAndTheSourceSaysWhy() {
        val clock = Clock(); val out = Audio()
        val r = BeatRunner(out, clock::schedule)
        r.start(plan(Player.PHONE_AUDIO), 100)
        out.callbacks.getValue(Player.PHONE_AUDIO)(null, "phone_audio_written+rtt")
        out.callbacks.getValue(Player.PHONE_AUDIO)(null, "phone_audio_no_timestamp+rtt")
        assertNull(r.beatStartMs)
        assertEquals("phone_audio_no_timestamp+rtt", r.beatStartSource)
        assertEquals(Place.EARBUDS, r.placeUsed)
    }

    // ---- #4: a replace never removes the old file before the new one is in place

    @Test
    fun n4_replacingAPendingFileNeverLeavesItMissing() {
        val dir = Files.createTempDirectory("pilot").toFile()
        PilotFileWriter.write(dir, "pending_x.txt", "1", replace = true)
        var targetPresentAtRename = false
        val f = PilotFileWriter.write(dir, "pending_x.txt", "2", replace = true, rename = { from, to ->
            targetPresentAtRename = to.isFile
            from.renameTo(to)
        })
        assertTrue("the old file was still there when the new one replaced it", targetPresentAtRename)
        assertEquals("2", f!!.readText())
        // A file system that refuses the rename: the content is copied over in place.
        val g = PilotFileWriter.write(dir, "pending_x.txt", "3", replace = true, rename = { _, _ -> false })
        assertEquals("3", g!!.readText())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
        dir.deleteRecursively()
    }

    // ---- #6: with the study switch off, a boot does nothing new

    @Test
    fun n6_bootRestartsOnlyWhileThePilotStudyIsActive() {
        val calls = mutableListOf<String>()
        WatchBootReceiver.onBoot("android.intent.action.BOOT_COMPLETED", pilotActive = false, { calls += "restart" }, { calls += "recover" })
        assertEquals("study off: nothing", emptyList<String>(), calls)
        WatchBootReceiver.onBoot("android.intent.action.BOOT_COMPLETED", pilotActive = true, { calls += "restart" }, { calls += "recover" })
        assertEquals(listOf("restart", "recover"), calls)
        calls.clear()
        WatchBootReceiver.onBoot("android.intent.action.SCREEN_ON", pilotActive = true, { calls += "restart" }, { calls += "recover" })
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun n7_aRecoveredPressSaysSo() {
        // Accepted with no code change: recovered presses keep recovered=true (round 001, #5),
        // and pilot_analysis gives them no timings. This pins the field.
        val json = com.medicina.shared.pilot.PendingPress(
            "P01", 2, 3, Place.WRIST, Player.WATCH_HAPTIC, Place.WRIST, false, false, 100, 1_000,
        ).toRecord(com.medicina.shared.pilot.MotionSamples.EMPTY, null, recovered = true).toJson()
        assertTrue(json.contains("\"recovered\":true"))
    }
}
