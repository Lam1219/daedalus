package com.medicina.daedaluswatch.sensor

import com.medicina.daedaluswatch.sensor.TremorAnalyzer.Outcome
import com.medicina.daedaluswatch.sensor.TremorAnalyzer.Strength
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class TremorAnalyzerTest {

    private val degToRad = PI / 180

    /**
     * [seconds] of 50 Hz-ish samples (timing jitter ±4 ms): a tremor at [hz] with RMS [dps]
     * spread over two axes, slow arm sway at 0.8 Hz of RMS [swayDps], and sensor noise.
     */
    private fun record(
        hz: Double?, dps: Double, seconds: Double = 20.0, swayDps: Double = 0.0,
        noiseDps: Double = 0.2, seed: Long = 1,
    ): TremorAnalyzer.Result {
        val rnd = Random(seed)
        val t = ArrayList<Long>(); val x = ArrayList<Double>(); val y = ArrayList<Double>(); val z = ArrayList<Double>()
        // RMS of a sine is amplitude / √2; split across x and y so the summed power is dps².
        val amp = dps * degToRad
        val sway = swayDps * degToRad * Math.sqrt(2.0)
        var ms = 1_700_000_000_000.0
        val end = ms + seconds * 1000
        while (ms < end) {
            val s = (ms - 1_700_000_000_000.0) / 1000
            val tremor = if (hz == null) 0.0 else amp * sin(2 * PI * hz * s)
            val noise = { rnd.nextGaussian() * noiseDps * degToRad }
            t.add(ms.toLong())
            x.add(tremor + sway * sin(2 * PI * 0.8 * s) + noise())
            y.add(tremor * 0.9 + noise())
            z.add(noise())
            ms += 20 + (rnd.nextDouble() * 8 - 4)
        }
        return TremorAnalyzer.analyze(t.toLongArray(), x.toDoubleArray(), y.toDoubleArray(), z.toDoubleArray())
    }

    @Test fun findsFiveHertzRestTremor() {
        val r = record(5.0, 15.0)
        assertEquals(Outcome.TREMOR, r.outcome)
        assertEquals(5.0, r.frequencyHz!!, 0.1)
        assertEquals(Strength.MODERATE, r.strength)
    }

    @Test fun findsFrequencyAcrossTheBand() {
        for (hz in listOf(3.5, 4.2, 6.3, 7.5)) {
            val r = record(hz, 8.0, seed = (hz * 10).toLong())
            assertEquals("at $hz Hz", Outcome.TREMOR, r.outcome)
            assertEquals("at $hz Hz", hz, r.frequencyHz!!, 0.1)
        }
    }

    @Test fun bandRmsMatchesInput() {
        // Two axes: x at amp, y at 0.9·amp → RMS √(1 + 0.81)/√2 · amp ≈ 0.951 · amp in dps.
        val r = record(5.0, 20.0)
        assertEquals(20.0 * Math.sqrt(1.81 / 2), r.bandDps, 1.5)
    }

    @Test fun strengthLevels() {
        assertEquals(Strength.LIGHT, record(5.0, 4.0).strength)
        assertEquals(Strength.MODERATE, record(5.0, 20.0).strength)
        assertEquals(Strength.STRONG, record(5.0, 60.0).strength)
    }

    @Test fun stillHandHasNoTremor() {
        assertEquals(Outcome.NONE, record(null, 0.0).outcome)
    }

    @Test fun tinyRhythmBelowThresholdIsNone() {
        assertEquals(Outcome.NONE, record(5.0, 0.8).outcome)
    }

    @Test fun rhythmOutsideTheBandIsNone() {
        // Physiological tremor around 10 Hz is not a resting tremor.
        assertEquals(Outcome.NONE, record(10.0, 6.0).outcome)
    }

    @Test fun movingArmIsMoved() {
        assertEquals(Outcome.MOVED, record(5.0, 10.0, swayDps = 40.0).outcome)
    }

    @Test fun shortRecordingIsIncomplete() {
        assertEquals(Outcome.INCOMPLETE, record(5.0, 15.0, seconds = 8.0).outcome)
    }

    @Test fun gapIsIncomplete() {
        val t = LongArray(1000) { it * 20L + if (it > 500) 1000 else 0 }
        val v = DoubleArray(1000) { sin(2 * PI * 5 * it / 50.0) * 0.2 }
        assertEquals(Outcome.INCOMPLETE, TremorAnalyzer.analyze(t, v, v, v).outcome)
    }

    @Test fun readsSessionCsv() {
        val sb = StringBuilder("timestamp_ms,heart_rate_bpm,ax,ay,az,gx,gy,gz,breathing_rate_brpm,spo2_percent\n")
        for (i in 0 until 1000) {
            val g = 0.2 * sin(2 * PI * 4.6 * i / 50.0)
            sb.append("${1_700_000_000_000L + i * 20},72,0.1,0.2,9.8,$g,${g * 0.5},0.0,,\n")
        }
        sb.append("garbage,row\n")
        val r = TremorAnalyzer.analyzeCsv(sb.toString())
        assertEquals(Outcome.TREMOR, r.outcome)
        assertEquals(4.6, r.frequencyHz!!, 0.1)
    }

    @Test fun csvWithoutGyroColumnsIsIncomplete() {
        assertEquals(Outcome.INCOMPLETE, TremorAnalyzer.analyzeCsv("timestamp_ms,ax\n1,2\n").outcome)
    }
}
