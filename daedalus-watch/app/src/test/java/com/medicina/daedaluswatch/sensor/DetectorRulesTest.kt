package com.medicina.daedaluswatch.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same rule cases as daedalus_watch_kit/tests (test_fog.py, test_tremor_stream.py) for fog-1.1. */
class DetectorRulesTest {

    private fun win(sec: Int, ctx: Context, reg: Double = 0.9, fi: Double = 0.3, swing: Double = 60.0, grav: Double = 0.0) =
        WindowFeatures(
            tEndMs = sec * 1000L, tremorDps = 0.0, peakHz = 5.0, prominence = 1.0, slowDps = 30.0,
            motionDps = 40.0, meanRotationDps = 0.0, accStd = 1.0, gravityChangeDeg = grav,
            freezeIndex = fi, cadenceSpm = 100.0, stepRegularity = reg, armSwingDps = swing, context = ctx,
        )

    private fun walk(from: Int, until: Int, ctx: Context = Context.WALKING) = (from until until).map { win(it, ctx) }

    @Test
    fun stillSecondBreaksARunOfFreezeSeconds() {
        val ws = walk(4, 12) + walk(12, 14, Context.TURNING) + listOf(
            win(14, Context.ACTIVE_HAND, reg = 0.1, fi = 6.0),
            win(15, Context.STILL, reg = 0.1, fi = 0.2, swing = 1.0),
            win(16, Context.ACTIVE_HAND, reg = 0.1, fi = 6.0),
        )
        assertEquals(0, FogDetector.detect(ws).size)
    }

    @Test
    fun walkContinuesOverAOneSecondDropout() {
        val ws = walk(4, 10) + listOf(win(10, Context.ACTIVE_HAND, reg = 0.6, fi = 0.5)) + walk(11, 13) +
            listOf(win(13, Context.ACTIVE_HAND, reg = 0.1, fi = 6.0))
        val c = FogDetector.detect(ws)
        assertEquals(1, c.size)
        assertEquals(0L, c[0].walkStartMs)
    }

    @Test
    fun feedbackBoundsNeverInvert() {
        val p = TremorStream.afterFeedback(TremorParams(thresholdDps = 1.0), falsePositive = true, baseDps = 0.8)
        assertEquals(TremorStream.T_MIN, p.thresholdDps, 1e-12)
    }
}
