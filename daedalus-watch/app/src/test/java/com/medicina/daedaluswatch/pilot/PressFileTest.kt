package com.medicina.daedaluswatch.pilot

import com.medicina.daedaluswatch.sensor.Json
import com.medicina.shared.pilot.CheckIn
import com.medicina.shared.pilot.ClickSchedule
import com.medicina.shared.pilot.MotionRing
import com.medicina.shared.pilot.Place
import com.medicina.shared.pilot.Player
import com.medicina.shared.pilot.PressRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * B4/B5: the press file. A synthetic freeze goes through the motion ring into a press file, which
 * must parse as the format in pilot_analysis.py; when python3 with numpy is available the test
 * also runs pilot_analysis.load_press + press_timing on it.
 */
class PressFileTest {

    private val t0 = 1_794_570_000_000L
    private val g = 9.80665f

    /** Like tests/test_pilot_analysis.py's press_file: walk, freeze, press, walk again (seconds from t0). */
    private fun fillRing(ring: MotionRing, seconds: Int, walkUntilS: Double, resumeAtS: Double) {
        val fs = 50
        for (i in 0 until seconds * fs) {
            val t = i.toDouble() / fs
            val walking = t < walkUntilS || t >= resumeAtS
            val step = 100.0 / 60; val stride = 100.0 / 120
            val bump = if (walking) 2.0 * (sin(2 * PI * step * t) + 0.5 * sin(2 * PI * stride * t)) else 0.0
            val swing = if (walking) Math.toRadians(60.0) * sqrt(2.0) * sin(2 * PI * stride * t) else 0.0
            ring.add(t0 + (t * 1000).toLong(), (0.3 * bump).toFloat(), 0f, g + bump.toFloat(), 0f, swing.toFloat(), 0f)
        }
    }

    private fun record(ring: MotionRing, pressMs: Long, beatStartMs: Long) = PressRecord(
        participant = "P01", pressIndex = 12, dayIndex = 9, place = Place.POCKET,
        earbudsConnected = false, phoneCarried = true, paceSpm = 100,
        pressMs = pressMs, beatStartMs = beatStartMs, beatStopMs = beatStartMs + 8000, checkin = CheckIn.ALREADY_MOVING,
        samples = ring.snapshot(pressMs - 30_000, pressMs + 60_000),
        placeDrawn = Place.POCKET, player = Player.PHONE_VIBRATION, beatStartSource = "phone_vibration+rtt",
    )

    @Test
    fun ringKeepsOnlyTheLastSamplesInOrder() {
        val ring = MotionRing(capacity = 100)
        for (i in 0 until 250) ring.add(i.toLong(), i.toFloat(), 0f, 0f, 0f, 0f, 0f)
        val s = ring.snapshot(0, 1000)
        assertEquals(100, s.size)
        assertEquals(150L, s.tMs.first())
        assertEquals(249L, s.tMs.last())
        assertEquals(249f, s.acc[0].last())
        assertNull(ring.snapshot(0, 1000, hasGyro = false).gyr)
        assertEquals(250L, ring.added)
    }

    @Test
    fun pressFileHasTheAnalysisFormatAnd90sOfMotion() {
        val ring = MotionRing()
        fillRing(ring, seconds = 110, walkUntilS = 25.0, resumeAtS = 33.0)
        val pressMs = t0 + 28_000
        val rec = record(ring, pressMs, pressMs + 500)
        @Suppress("UNCHECKED_CAST")
        val d = Json.parse(rec.toJson()) as Map<String, Any?>
        assertEquals("pilot-1.0", d["version"])
        assertEquals("P01", d["participant"])
        assertEquals(12.0, d["press_index"])
        assertEquals("pocket", d["place"])
        assertEquals("already_moving", d["checkin"])
        assertEquals(pressMs.toDouble(), d["press_ms"])
        @Suppress("UNCHECKED_CAST")
        val s = d["samples"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val t = s["t_ms"] as List<Double>
        // The ring starts at t0, so this press has 28 s before it and 60 s after.
        assertEquals((pressMs - 28_000).toDouble(), t.first(), 0.0)
        assertTrue(t.last() <= pressMs + 60_000)
        @Suppress("UNCHECKED_CAST")
        val acc = s["acc"] as List<List<Double>>
        assertEquals(3, acc.size)
        assertEquals(t.size, acc[2].size)
        assertTrue(rec.fileName.startsWith("pilot_P01_00012_"))
        // A null check-in and stop are written as JSON null.
        @Suppress("UNCHECKED_CAST")
        val d2 = Json.parse(rec.copy(checkin = null, beatStopMs = null).toJson()) as Map<String, Any?>
        assertTrue(d2.containsKey("checkin") && d2["checkin"] == null && d2["beat_stop_ms"] == null)
    }

    @Test
    fun pythonPilotAnalysisReadsTheFile() {
        val ring = MotionRing()
        fillRing(ring, seconds = 120, walkUntilS = 55.0, resumeAtS = 63.0)
        val pressMs = t0 + 58_000
        val rec = record(ring, pressMs, pressMs + 500)
        val dir = File("build/pilot-press-sample").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, rec.fileName).apply { writeText(rec.toJson()) }
        val kit = File("../../daedalus_watch_kit").absoluteFile
        val script = """
            import sys
            from pathlib import Path
            from daedalus_watch_kit.pilot_analysis import load_press, press_timing, analyse
            t = press_timing(load_press(Path(sys.argv[1])))
            analyse(Path(sys.argv[1]).parent)
            print(t.stop_to_beat_s, t.press_to_steps_s)
        """.trimIndent()
        val proc = try {
            ProcessBuilder("python3", "-c", script, f.absolutePath).directory(kit).redirectErrorStream(true).start()
        } catch (e: Exception) {
            println("python3 not available, skipping the Python check: ${e.message}"); return
        }
        proc.waitFor(120, TimeUnit.SECONDS)
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.exitValue() != 0 && out.contains("No module named 'numpy'")) {
            println("numpy not available, skipping the Python check"); return
        }
        assertEquals("python said: $out", 0, proc.exitValue())
        val (stop, steps) = out.lines().last().split(" ").map { it.toDouble() }
        println("pilot_analysis on the Kotlin press file: stop_to_beat_s=$stop press_to_steps_s=$steps")
        // Walking stopped at 55 s, beat at 58.5 s; steps again at 63 s (5 s after the press).
        assertEquals(3.5, stop, 0.5)
        assertEquals(5.0, steps, 0.5)
    }

    @Test
    fun clickScheduleDoesNotDriftOverTwoMinutes() {
        for (bpm in listOf(60, 97, 100, 113, 140)) {
            val beats = 2 * bpm // two minutes
            val frame = ClickSchedule.beatFrame(beats.toLong(), bpm)
            val ideal = beats * 60.0 / bpm * ClickSchedule.SAMPLE_RATE
            assertTrue("bpm $bpm: ${frame - ideal} frames", abs(frame - ideal) < 1.0)
        }
        // The filled track has a click exactly where the schedule says, across chunk borders.
        val bpm = 97
        val chunk = ShortArray(480)
        var clicks = 0
        var from = 0L
        while (from < ClickSchedule.SAMPLE_RATE * 10L) {
            ClickSchedule.fill(chunk, from, bpm)
            for (i in chunk.indices) {
                val f = from + i
                val k0 = f * bpm / (ClickSchedule.SAMPLE_RATE * 60L)
                // Integer division can land one beat early: the beat starting at or before f.
                val k = if (ClickSchedule.beatFrame(k0 + 1, bpm) <= f) k0 + 1 else k0
                if (f == ClickSchedule.beatFrame(k, bpm)) { clicks++; assertEquals(ClickSchedule.click[0], chunk[i]) }
                if (f == ClickSchedule.beatFrame(k, bpm) + 5) assertEquals(ClickSchedule.click[5], chunk[i])
            }
            from += chunk.size
        }
        assertEquals((10 * bpm + 59) / 60, clicks)
    }
}
