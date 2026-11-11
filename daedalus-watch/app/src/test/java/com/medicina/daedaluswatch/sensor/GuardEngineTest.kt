package com.medicina.daedaluswatch.sensor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** [StreamResampler] and [GuardEngine.fmt]; the guard events themselves are in GoldenVectorsTest. */
class GuardEngineTest {

    private fun channels(t: LongArray) = Array(6) { c -> DoubleArray(t.size) { sin(2 * PI * (c + 1) * t[it] / 1000.0) + c } }

    private fun resampleAll(r: StreamResampler, t: LongArray, ch: Array<DoubleArray>): List<Pair<Long, DoubleArray>> {
        val out = ArrayList<Pair<Long, DoubleArray>>()
        for (i in t.indices) out += r.pushAll(t[i].toDouble(), DoubleArray(6) { ch[it][i] }).second
        return out
    }

    @Test fun evenInputPassesThrough() {
        val t = LongArray(200) { 1_000_000L + it * 20 }
        val ch = channels(t)
        val r = StreamResampler()
        val out = resampleAll(r, t, ch)
        assertEquals(t.size, out.size)
        for (i in t.indices) {
            assertEquals(t[i], out[i].first)
            assertArrayEquals(DoubleArray(6) { ch[it][i] }, out[i].second, 0.0)
        }
        assertEquals(0, r.restarts)
    }

    @Test fun jitteredInputEqualsResampleUniform() {
        val rnd = java.util.Random(7)
        var tt = 5_000L
        val t = LongArray(300) { val x = tt; tt += 20 + rnd.nextInt(11) - 5; x }
        val ch = channels(t)
        val out = resampleAll(StreamResampler(), t, ch)
        val ref = StreamFeatures.resampleUniform(t, *ch)
        assertEquals(ref.tMs.size, out.size)
        for (i in out.indices) {
            assertEquals(ref.tMs[i], out[i].first)
            for (c in 0 until 6) assertEquals("[$i][$c]", ref.channels[c][i], out[i].second[c], 0.0)
        }
    }

    @Test fun gapAndBackwardTimeRestart() {
        val r = StreamResampler()
        assertFalse(r.pushAll(0.0, DoubleArray(6)).first)
        assertEquals(listOf(20L), r.pushAll(20.0, DoubleArray(6) { 1.0 }).second.map { it.first })
        // 251 ms gap: a new grid from 271.
        val (restarted, pts) = r.pushAll(271.0, DoubleArray(6) { 2.0 })
        assertTrue(restarted)
        assertEquals(listOf(271L), pts.map { it.first })
        assertArrayEquals(DoubleArray(6) { 2.0 }, pts[0].second, 0.0)
        // Exactly 250 ms is not a gap: grid points 291 .. 511 are interpolated.
        val (r2, pts2) = r.pushAll(521.0, DoubleArray(6) { 3.0 })
        assertFalse(r2)
        assertEquals((1..12).map { 271L + it * 20 }, pts2.map { it.first })
        // A repeated time is skipped; a time going backwards restarts.
        val (r3, pts3) = r.pushAll(521.0, DoubleArray(6))
        assertFalse(r3)
        assertTrue(pts3.isEmpty())
        assertEquals(1, r.restarts)
        assertTrue(r.pushAll(520.0, DoubleArray(6)).first)
        assertEquals(2, r.restarts)
    }

    @Test fun gapStartsNewExtractorAndCounts() {
        val t = LongArray(300) { if (it < 150) it * 20L else 10_000L + it * 20L }
        val ch = channels(t)
        val events = GuardEngine.run(GuardEngine.MODE_REST, t, arrayOf(ch[0], ch[1], ch[2]), arrayOf(ch[3], ch[4], ch[5]))
        // Each 3 s run gives 2 windows (at 2 s and 3 s); no window spans the gap.
        assertEquals("type=guard;mode=rest;windows=4;gaps=1", events.last().notes)
    }

    @Test fun fmtMatchesPython() {
        assertEquals("0.12", GuardEngine.fmt(0.125, 2))     // exact tie: to even
        assertEquals("0.38", GuardEngine.fmt(0.375, 2))
        assertEquals("2.67", GuardEngine.fmt(2.675, 2))     // binary value is below the tie
        assertEquals("0.2", GuardEngine.fmt(0.25, 1))
        assertEquals("6.00", GuardEngine.fmt(6.0, 2))
        assertEquals("-0.00", GuardEngine.fmt(-0.001, 2))
        assertEquals("10854.14", GuardEngine.fmt(10854.1389, 2))
    }
}
