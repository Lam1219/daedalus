package com.medicina.daedaluswatch.pilot

import android.bluetooth.BluetoothClass
import com.medicina.daedaluswatch.haptics.HapticService
import com.medicina.daedaluswatch.sensor.Json
import com.medicina.shared.pilot.AudioRoutes
import com.medicina.shared.pilot.BeatMessage
import com.medicina.shared.pilot.BeatOutput
import com.medicina.shared.pilot.BeatRunner
import com.medicina.shared.pilot.CheckIn
import com.medicina.shared.pilot.MotionRing
import com.medicina.shared.pilot.MotionSamples
import com.medicina.shared.pilot.PendingPress
import com.medicina.shared.pilot.PilotFileWriter
import com.medicina.shared.pilot.PilotSettings
import com.medicina.shared.pilot.Place
import com.medicina.shared.pilot.Player
import com.medicina.shared.pilot.PressPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Review round 001 (Android). Each test names its finding. */
class RoundOneFixesTest {

    // ---- fakes shared by the runner tests

    private class Out(private val silent: Set<Player> = emptySet(), private val source: String = "test") : BeatOutput {
        val started = mutableListOf<Player>()
        val paused = mutableListOf<Pair<Player, Boolean>>()
        override fun start(player: Player, bpm: Int, onStarted: (Long, String) -> Unit, onFailed: (String) -> Unit) {
            started += player
            if (player !in silent) onStarted(5_000, source)
        }
        override fun stop(player: Player) {}
        override fun pause(player: Player) { paused += player to true }
        override fun resume(player: Player) { paused += player to false }
    }

    private class Clock {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        var now = 0L
        fun schedule(d: Long, b: () -> Unit): () -> Unit { val t = (now + d) to b; tasks += t; return { tasks.remove(t) } }
        fun advance(ms: Long) { now += ms; tasks.filter { it.first <= now }.forEach { tasks.remove(it); it.second() } }
    }

    private val base = PressPlanner.plan(PilotSettings(true, "P01", 1, 0), 0, 30, false, false, false)
    private fun plan(p: Player) = base.copy(drawn = p.place, player = p)

    // ---- #12 (with #4): the ring keeps samples by age, whatever the rate

    @Test
    fun n12_ringKeeps150sEvenWhenTheSensorDeliversTwiceAsFast() {
        val ring = MotionRing()
        for (i in 0 until 200 * 100) ring.add(i * 10L, 1f, 0f, 0f, 0f, 0f, 0f) // 100 Hz for 200 s
        val newest = (200 * 100 - 1) * 10L
        val s = ring.snapshot(newest - 150_000, newest)
        assertEquals("150 s at 100 Hz, nothing lost", 15_001, s.size)
        assertEquals(newest - 150_000, s.tMs.first())
    }

    // ---- #5: a pending press file at the press, finished later as a recovered press

    @Test
    fun n5_pendingPressRoundTripsAndBecomesARecoveredPressFile() {
        val p = PendingPress(
            "P03", 12, 9, Place.POCKET, Player.PHONE_VIBRATION, null, false, true, 104, 1_794_570_000_000,
            beatStartMs = 1_794_570_000_400, beatStartSource = "phone_vibration+rtt",
        )
        assertEquals(p, PendingPress.decode(p.encode()))
        assertTrue("not picked up by the analysis or the transfer", p.fileName.startsWith("pending_") && !p.fileName.endsWith(".json"))
        assertEquals("pilot_P03_00012_1794570000000.json", p.pressFileName)
        val rec = p.toRecord(MotionSamples.EMPTY, null, recovered = true)
        @Suppress("UNCHECKED_CAST")
        val d = Json.parse(rec.toJson()) as Map<String, Any?>
        assertEquals("pocket", d["place"])
        assertEquals(true, d["recovered"])
        assertNull(d["checkin"])
        assertNull(d["beat_stop_ms"])
        assertEquals(1_794_570_000_400.0, d["beat_start_ms"])
        // Nothing confirmed: no beat start, never the press time.
        val unconfirmed = p.copy(beatStartMs = null, beatStartSource = null).toRecord(MotionSamples.EMPTY, null, true)
        assertNull(unconfirmed.beatStartMs)
        assertNull(PendingPress.decode("garbage"))
    }

    // ---- #6: "I'm stuck" is on the check-in screen, first

    @Test
    fun n6_checkinScreenHasImStuckFirst() {
        val xml = File("src/main/res/layout/activity_main.xml").readText()
        val screen = xml.substring(xml.indexOf("@+id/screen_checkin"))
        val stuck = screen.indexOf("@+id/btn_checkin_stuck")
        assertTrue("I'm stuck on the check-in", stuck > 0)
        assertTrue("above the answers", stuck < screen.indexOf("@+id/btn_checkin_helped"))
    }

    // ---- #7: no beat start from a play() call; Bluetooth flagged; longer wait for watch audio

    @Test
    fun n7_aPlayCallTimeIsNotABeatStart() {
        val r = BeatRunner(Out(source = "audio_play_call_bt"), Clock()::schedule)
        r.start(plan(Player.WATCH_AUDIO), 100)
        assertTrue(r.confirmed)
        assertNull("estimate only: no beat_start_ms", r.beatStartMs)
        assertEquals(Place.EARBUDS, r.placeUsed)
        assertEquals("audio_play_call_bt", r.beatStartSource)
        val phone = BeatRunner(Out(source = "phone_audio_play_call+rtt"), Clock()::schedule)
        phone.start(plan(Player.PHONE_AUDIO), 100)
        assertNull(phone.beatStartMs)
        val ok = BeatRunner(Out(source = "audio_timestamp"), Clock()::schedule)
        ok.start(plan(Player.WATCH_AUDIO), 100)
        assertEquals(5_000L, ok.beatStartMs)
    }

    @Test
    fun n7_bluetoothAudioIsFlaggedInThePressFile() {
        val p = PendingPress("P01", 1, 1, Place.EARBUDS, Player.WATCH_AUDIO, Place.EARBUDS, true, false, 100, 1_000, 1_200, "audio_timestamp_bt")
        assertTrue(p.toRecord(MotionSamples.EMPTY, CheckIn.HELPED).toJson().contains("\"bt_latency_unknown\":true"))
        assertTrue(p.copy(beatStartSource = "phone_audio_timestamp_bt+rtt").toRecord(MotionSamples.EMPTY, null).btLatencyUnknown)
        assertFalse(p.copy(beatStartSource = "audio_timestamp").toRecord(MotionSamples.EMPTY, null).btLatencyUnknown)
        assertFalse(p.copy(beatStartSource = "watch_motor").toRecord(MotionSamples.EMPTY, null).btLatencyUnknown)
    }

    @Test
    fun n7_watchAudioGets2_5sToConfirmAndStillFallsBack() {
        val clock = Clock()
        val out = Out(silent = setOf(Player.WATCH_AUDIO))
        val r = BeatRunner(out, clock::schedule)
        r.start(plan(Player.WATCH_AUDIO), 100)
        clock.advance(2_000)
        assertEquals("still waiting at 2 s", listOf(Player.WATCH_AUDIO), out.started)
        clock.advance(500)
        assertEquals(listOf(Player.WATCH_AUDIO, Player.WATCH_HAPTIC), out.started)
        assertEquals(Place.WRIST, r.placeUsed)
        // The phone keeps its 1 s.
        val c2 = Clock(); val o2 = Out(silent = setOf(Player.PHONE_AUDIO))
        BeatRunner(o2, c2::schedule).start(plan(Player.PHONE_AUDIO), 100)
        c2.advance(1_000)
        assertEquals(listOf(Player.PHONE_AUDIO, Player.WATCH_HAPTIC), o2.started)
    }

    // ---- #8: earbuds are headphones or headsets, not speakers, cars or TVs

    @Test
    fun n8_onlyHeadphoneBluetoothClassesCountAsEarbuds() {
        assertTrue(AudioRoutes.isHeadphoneClass(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES))
        assertTrue(AudioRoutes.isHeadphoneClass(BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET))
        for (c in listOf(
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE, BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX, BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED,
        )) assertFalse("class $c", AudioRoutes.isHeadphoneClass(c))
    }

    // ---- #9 and #13: backup rules and the boot receiver on the watch

    @Test
    fun n9_watchPilotFilesAndSettingsAreExcludedFromBackup() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertEquals("cloud backup and device transfer", 2, Regex("domain=\"file\" path=\"pilot/\"").findAll(rules).count())
        assertEquals(2, Regex("domain=\"sharedpref\" path=\"pilot.xml\"").findAll(rules).count())
        val legacy = File("src/main/res/xml/backup_rules.xml").readText()
        assertTrue(legacy.contains("path=\"pilot/\"") && legacy.contains("path=\"pilot.xml\""))
    }

    @Test
    fun n13_watchRestartsAfterABoot() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        val receiver = manifest.substring(manifest.indexOf(".sensor.WatchBootReceiver"))
        assertTrue(receiver.substring(0, receiver.indexOf("</receiver>")).contains("android.intent.action.BOOT_COMPLETED"))
    }

    // ---- #11: a pilot press writes no session CSV (nothing for cloud backup)

    @Test
    fun n11_pilotPressWritesNoSessionCsv() {
        assertFalse(HapticService.writesSessionCsv(pilotPress = true))
        assertTrue("study off: unchanged", HapticService.writesSessionCsv(pilotPress = false))
    }

    // ---- #14: the press-file write never crashes and never replaces a file

    @Test
    fun n14_writerKeepsBothOnAClashAndFailsWithoutThrowing() {
        val dir = Files.createTempDirectory("pilot").toFile()
        val a = PilotFileWriter.write(dir, "pilot_P01_00001_1.json", "{\"a\":1}")
        val b = PilotFileWriter.write(dir, "pilot_P01_00001_1.json", "{\"a\":2}")
        assertNotNull(a); assertNotNull(b)
        assertEquals("pilot_P01_00001_1-2.json", b!!.name)
        assertEquals("{\"a\":1}", a!!.readText())
        assertTrue("no .part left", dir.listFiles()!!.none { it.name.endsWith(".part") })
        // A pending file is replaced in place.
        PilotFileWriter.write(dir, "pending_x.txt", "1", replace = true)
        PilotFileWriter.write(dir, "pending_x.txt", "2", replace = true)
        assertEquals("2", File(dir, "pending_x.txt").readText())
        // A "directory" that is a file: the write fails twice, logs, and returns null.
        val notADir = File(dir, "blocker").apply { writeText("x") }
        val logs = mutableListOf<String>()
        assertNull(PilotFileWriter.write(notADir, "pilot_P01_00002_2.json", "{}") { logs += it })
        assertEquals("tried twice", 2, logs.size)
        dir.deleteRecursively()
    }

    // ---- #24: pause and resume reach whoever plays the beat

    @Test
    fun n24_pauseAndResumeReachThePlayer() {
        val out = Out()
        val r = BeatRunner(out, Clock()::schedule)
        r.start(plan(Player.PHONE_VIBRATION), 100)
        r.pause(); r.pause(); r.resume()
        assertEquals(listOf(Player.PHONE_VIBRATION to true, Player.PHONE_VIBRATION to false), out.paused)
        for (m in listOf(BeatMessage.Pause("P01-3", true), BeatMessage.Pause("P01-3", false))) {
            assertEquals(m, BeatMessage.decode(m.encode()))
        }
    }
}
