package com.medicina.daedaluswatch.pilot

import com.medicina.daedaluswatch.sensor.WatchBootReceiver
import com.medicina.shared.pilot.BeatOutput
import com.medicina.shared.pilot.BeatRunner
import com.medicina.shared.pilot.FirstClick
import com.medicina.shared.pilot.PilotSettings
import com.medicina.shared.pilot.Place
import com.medicina.shared.pilot.Player
import com.medicina.shared.pilot.PressPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review round 003 (Android). Each test names its finding. */
class RoundThreeFixesTest {

    private class Out : BeatOutput {
        val started = mutableListOf<Player>()
        val callbacks = HashMap<Player, (Long?, String) -> Unit>()
        override fun start(player: Player, bpm: Int, onStarted: (Long?, String) -> Unit, onFailed: (String) -> Unit) {
            started += player
            callbacks[player] = onStarted
            if (player == Player.WATCH_HAPTIC) onStarted(9_000, "watch_motor")
        }
        override fun stop(player: Player) {}
    }

    private val base = PressPlanner.plan(PilotSettings(true, "P01", 1, 0), 0, 30, false, false, false)
    private fun plan(p: Player) = base.copy(drawn = p.place, player = p)
    private fun runner(out: Out) = BeatRunner(out, schedule = { _, _ -> {} })

    // ---- #4: audio written but never shown as presented is lost, so the wrist takes over

    @Test
    fun n4_noTimestampWithinTheWaitIsLost() {
        assertEquals(FirstClick.Outcome.WAIT, FirstClick.decide(presented = false, waitedMs = 1_999))
        assertEquals(FirstClick.Outcome.MEASURED, FirstClick.decide(presented = true, waitedMs = 50))
        assertEquals(FirstClick.Outcome.LOST, FirstClick.decide(presented = false, waitedMs = 2_001))
    }

    @Test
    fun n4_losingTheAudioAfterTheWaitPutsTheBeatOnTheWristAndRecordsWhen() {
        // What the click player does on LOST: report no start, then lost.
        val out = Out()
        val r = runner(out)
        r.start(plan(Player.WATCH_AUDIO), 100)
        out.callbacks.getValue(Player.WATCH_AUDIO)(null, "audio_written_bt")
        out.callbacks.getValue(Player.WATCH_AUDIO)(null, "audio_no_timestamp_bt")
        r.lost("audio_no_timestamp", nowMs = 2_500)
        assertEquals(listOf(Player.WATCH_AUDIO, Player.WATCH_HAPTIC), out.started)
        assertEquals(Player.WATCH_HAPTIC, r.player)
        assertEquals(2_500L, r.fallbackAtMs)
        assertEquals("audio_no_timestamp", r.fallbackReason)
        assertNull(r.beatStartMs)
        assertEquals("audio_no_timestamp_bt", r.beatStartSource)
    }

    // ---- #5: an older phone build's play() call time is never recorded as the start

    @Test
    fun n5_aPlayCallSourceNeverGivesAStart() {
        val out = Out()
        val r = runner(out)
        r.start(plan(Player.PHONE_AUDIO), 100)
        out.callbacks.getValue(Player.PHONE_AUDIO)(1_234, "phone_audio_play_call+rtt")
        assertTrue(r.confirmed)
        assertNull("no start from a play() call", r.beatStartMs)
        assertEquals(Place.EARBUDS, r.placeUsed)
        // A later real timestamp still counts.
        out.callbacks.getValue(Player.PHONE_AUDIO)(1_300, "phone_audio_timestamp+rtt")
        assertEquals(1_300L, r.beatStartMs)
    }

    // ---- #7: boot finishes unfinished presses whatever the switch says, and starts nothing when off

    @Test
    fun n7_bootRecoversPressesWithTheStudyOff() {
        val calls = mutableListOf<String>()
        WatchBootReceiver.onBoot("android.intent.action.BOOT_COMPLETED", pilotActive = false, { calls += "restart" }, { calls += "recover" })
        assertEquals(listOf("recover"), calls)
    }
}
