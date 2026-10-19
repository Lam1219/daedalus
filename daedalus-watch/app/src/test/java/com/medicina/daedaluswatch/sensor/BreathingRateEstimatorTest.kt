package com.medicina.daedaluswatch.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BreathingRateEstimatorTest {

    /** Beats at ~70 bpm whose interval swings with breathing at [breathsPerMin]. */
    private fun feed(estimator: BreathingRateEstimator, breathsPerMin: Double, seconds: Double, swingMs: Double = 40.0) {
        var t = 0.0
        while (t < seconds * 1000) {
            val ibi = 857.0 + swingMs * sin(2 * PI * (breathsPerMin / 60.0) * t / 1000.0)
            estimator.addInterval(ibi.toFloat())
            t += ibi
        }
    }

    @Test fun findsFifteenBreathsPerMinute() {
        val e = BreathingRateEstimator()
        feed(e, 15.0, 90.0)
        assertEquals(15f, assertNotNullValue(e.estimate()), 1f)
    }

    @Test fun findsTenBreathsPerMinute() {
        val e = BreathingRateEstimator()
        feed(e, 10.0, 90.0)
        assertEquals(10f, assertNotNullValue(e.estimate()), 1f)
    }

    @Test fun needsAtLeastThirtySeconds() {
        val e = BreathingRateEstimator()
        feed(e, 15.0, 20.0)
        assertNull(e.estimate())
    }

    @Test fun steadyHeartbeatHasNoBreathingRhythm() {
        val e = BreathingRateEstimator()
        feed(e, 15.0, 90.0, swingMs = 0.0)
        assertNull(e.estimate())
    }

    @Test fun ignoresImplausibleAndEctopicBeats() {
        val e = BreathingRateEstimator()
        feed(e, 12.0, 45.0)
        e.addInterval(150f)   // implausible
        e.addInterval(1500f)  // sudden jump (ectopic)
        feed(e, 12.0, 45.0)
        assertEquals(12f, assertNotNullValue(e.estimate()), 1f)
    }

    private fun assertNotNullValue(v: Float?): Float {
        assertNotNull(v)
        return v!!
    }
}
