package com.medicina.daedaluswatch.pilot

import com.medicina.shared.pilot.BeatClock
import com.medicina.shared.pilot.BeatMessage
import com.medicina.shared.pilot.BeatOutput
import com.medicina.shared.pilot.BeatRunner
import com.medicina.shared.pilot.PhoneStatus
import com.medicina.shared.pilot.PilotSettings
import com.medicina.shared.pilot.Place
import com.medicina.shared.pilot.Player
import com.medicina.shared.pilot.PressPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Every press gets a beat": whatever the drawn place does, the wrist takes over at once. */
class BeatRunnerTest {

    /** A fake set of players: each either starts (at a given time), fails, or never answers. */
    private class FakeOutput(private val behaviour: Map<Player, String>) : BeatOutput {
        val started = mutableListOf<Player>()
        val stopped = mutableListOf<Player>()
        val playing = mutableSetOf<Player>()
        val pendingStart = HashMap<Player, (Long, String) -> Unit>()
        val pendingFail = HashMap<Player, (String) -> Unit>()

        override fun start(player: Player, bpm: Int, onStarted: (Long, String) -> Unit, onFailed: (String) -> Unit) {
            started += player
            pendingStart[player] = onStarted
            pendingFail[player] = onFailed
            when (behaviour[player] ?: "ok") {
                "ok" -> { playing += player; onStarted(1_000L + started.size, "test") }
                "fail" -> onFailed("unavailable")
                "silent" -> Unit // never answers (phone out of reach)
            }
        }

        override fun stop(player: Player) { stopped += player; playing -= player }
    }

    /** A manual clock for the runner's timeout. */
    private class Clock {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        var now = 0L
        fun schedule(delay: Long, block: () -> Unit): () -> Unit {
            val t = (now + delay) to block
            tasks += t
            return { tasks.remove(t) }
        }
        fun advance(ms: Long) {
            now += ms
            tasks.filter { it.first <= now }.forEach { tasks.remove(it); it.second() }
        }
    }

    private val wristPlan = PressPlanner.plan(PilotSettings(true, "P01", 1, 0), 0, 30, false, false, false)

    private fun plan(player: Player) = wristPlan.copy(drawn = player.place, player = player)

    @Test
    fun everyPlayerThatFailsFallsBackToTheWristAtOnce() {
        for (p in Player.entries.filter { it != Player.WATCH_HAPTIC }) {
            val out = FakeOutput(mapOf(p to "fail"))
            val clock = Clock()
            val r = BeatRunner(out, clock::schedule)
            r.start(plan(p), 100)
            assertEquals("$p: tried, then the wrist", listOf(p, Player.WATCH_HAPTIC), out.started)
            assertEquals(Place.WRIST, r.placeUsed)
            assertNotNull(r.beatStartMs)
            assertEquals("unavailable", r.fallbackReason)
            assertTrue(Player.WATCH_HAPTIC in out.playing)
        }
    }

    @Test
    fun aPhoneThatNeverAnswersFallsBackAfterOneSecondAndIsToldToStop() {
        for (p in listOf(Player.PHONE_AUDIO, Player.PHONE_VIBRATION)) {
            val out = FakeOutput(mapOf(p to "silent"))
            val clock = Clock()
            val r = BeatRunner(out, clock::schedule)
            r.start(plan(p), 100)
            clock.advance(999)
            assertNull("no beat yet, still waiting", r.beatStartMs)
            clock.advance(1)
            assertEquals(listOf(p, Player.WATCH_HAPTIC), out.started)
            assertTrue("the silent phone is told to stop", p in out.stopped)
            assertEquals(Place.WRIST, r.placeUsed)
            assertEquals("no_reply", r.fallbackReason)
            // A late "started" from the phone changes nothing.
            out.pendingStart[p]!!(5_000L, "late")
            assertEquals(Place.WRIST, r.placeUsed)
        }
    }

    @Test
    fun aPhoneThatAnswersInTimeKeepsItsPlace() {
        val out = FakeOutput(mapOf(Player.PHONE_VIBRATION to "silent"))
        val clock = Clock()
        val r = BeatRunner(out, clock::schedule)
        r.start(plan(Player.PHONE_VIBRATION), 100)
        clock.advance(600)
        out.pendingStart[Player.PHONE_VIBRATION]!!(1234L, "phone_vibration+rtt")
        clock.advance(2000)
        assertEquals(Place.POCKET, r.placeUsed)
        assertEquals(1234L, r.beatStartMs)
        assertEquals(listOf(Player.PHONE_VIBRATION), out.started)
        assertNull(r.fallbackReason)
    }

    @Test
    fun losingTheEarbudsMidBeatSwitchesToTheWristAndKeepsWhereItStarted() {
        val out = FakeOutput(emptyMap())
        val r = BeatRunner(out, Clock()::schedule)
        r.start(plan(Player.WATCH_AUDIO), 100)
        assertEquals(Place.EARBUDS, r.placeUsed)
        r.lost("headphones_removed", nowMs = 9_000)
        assertEquals(listOf(Player.WATCH_AUDIO, Player.WATCH_HAPTIC), out.started)
        assertTrue(Player.WATCH_HAPTIC in out.playing)
        assertEquals(Place.EARBUDS, r.placeUsed)
        assertEquals(9_000L, r.fallbackAtMs)
    }

    @Test
    fun noDrawEverEndsInSilence() {
        // Every availability, every outcome of the drawn player: something always plays.
        val settings = PilotSettings(true, "P02", 987654321, 0)
        for (day in listOf(-1L, 0L, 3L, 8L, 15L, 22L)) for (i in 0L until 60L)
            for (we in listOf(false, true)) for (pe in listOf(false, true)) for (pc in listOf(false, true))
                for (outcome in listOf("ok", "fail", "silent")) {
                    val plan = PressPlanner.plan(settings, i, day, we, pe, pc)
                    val out = FakeOutput(Player.entries.filter { it != Player.WATCH_HAPTIC }.associateWith { outcome })
                    val clock = Clock()
                    val r = BeatRunner(out, clock::schedule)
                    r.start(plan, 100)
                    clock.advance(1_000)
                    assertTrue("$plan $outcome: something plays", out.playing.isNotEmpty())
                    assertNotNull("$plan $outcome: beat start recorded", r.beatStartMs)
                    if (outcome != "ok") assertEquals(Place.WRIST, r.placeUsed)
                }
    }

    @Test
    fun earbudsPlayOnTheWatchWhenItHasThemOtherwiseOnThePhone() {
        val s = PilotSettings(true, "P03", 5, 0)
        // Day 0, press 1 with earbuds available rotates to earbuds.
        assertEquals(Player.WATCH_AUDIO, PressPlanner.plan(s, 1, 0, watchEarbuds = true, phoneEarbuds = true, phoneCarried = false).player)
        assertEquals(Player.PHONE_AUDIO, PressPlanner.plan(s, 1, 0, watchEarbuds = false, phoneEarbuds = true, phoneCarried = false).player)
        assertEquals(Player.PHONE_VIBRATION, PressPlanner.plan(s, 1, 0, watchEarbuds = false, phoneEarbuds = false, phoneCarried = true).player)
        assertEquals(Player.WATCH_HAPTIC, PressPlanner.plan(s, 0, 0, watchEarbuds = true, phoneEarbuds = true, phoneCarried = true).player)
    }

    @Test
    fun phoneBeatStartIsPutOnTheWatchClockWithoutTrustingThePhoneClock() {
        // Watch sends at 1000, gets the reply at 1300. The phone (clock 10 s off) took 100 ms
        // between receiving and replying, and its first pulse was 40 ms before it replied.
        // One-way = (300 - 100) / 2 = 100: phone received at watch 1100, replied at 1200, pulse at 1160.
        assertEquals(1160L, BeatClock.beatStartOnWatch(1000, 1300, 11_100, 11_160, 11_200))
    }

    @Test
    fun messagesSurviveARoundTrip() {
        val msgs = listOf(
            BeatMessage.Start("P01-4", Player.PHONE_AUDIO, 104, 600_000),
            BeatMessage.Stop("P01-4"),
            BeatMessage.Started("P01-4", 1, 2, 3, "audio_timestamp"),
            BeatMessage.Failed("P01-4", "no_headphones"),
        )
        for (m in msgs) assertEquals(m, BeatMessage.decode(m.encode()))
        val st = PhoneStatus(true, 12_000, null, "q1")
        assertEquals(st, PhoneStatus.decode(st.encode()))
        assertEquals(PhoneStatus(false, null, false, ""), PhoneStatus.decode(PhoneStatus(false, null, false).encode()))
        val s = PilotSettings(true, "P07", Long.MAX_VALUE.and(0x7FFFFFFF), 20_412, false)
        assertEquals(s, PilotSettings.decode(s.encode()))
        assertTrue(s.isValid)
        assertTrue(!PilotSettings(true, "Jane Doe", 1, 0).isValid)
        assertTrue(!PilotSettings(true, "P1", 1L shl 31, 0).isValid)
    }
}
