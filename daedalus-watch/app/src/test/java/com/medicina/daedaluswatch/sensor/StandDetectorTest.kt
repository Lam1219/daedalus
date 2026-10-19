package com.medicina.daedaluswatch.sensor

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class StandDetectorTest {

    /** One recorded stretch: pressure (hPa) and accel noise level per 100 ms step. */
    private class Sim(seed: Long = 7) {
        val rnd = Random(seed)
        val detector = StandDetector()
        var t = 0L
        var p = 1013.0f
        var detections = mutableListOf<Long>()

        /** [seconds] of samples at 10 Hz; pressure moves by [dp] in total; accel noise std [motion]. */
        fun run(seconds: Double, dp: Float = 0f, motion: Float = 0.2f) {
            val steps = (seconds * 10).toInt()
            repeat(steps) {
                t += 100
                p += dp / steps
                val g = 9.81f + (rnd.nextGaussian() * motion).toFloat()
                detector.onAccel(t, 0f, 0f, g)
                val noisy = p + (rnd.nextGaussian() * 0.005).toFloat()
                if (detector.onPressure(t, noisy)) detections.add(t)
            }
        }
    }

    @Test fun detectsSitToStand() {
        val s = Sim()
        s.run(15.0)                              // sitting still
        s.run(2.0, dp = -0.035f, motion = 2.0f)  // stand up: wrist rises ~30 cm, body moves
        s.run(8.0)                               // standing
        assertEquals(1, s.detections.size)
    }

    @Test fun ignoresBarometerNoiseWhileSitting() {
        val s = Sim()
        s.run(60.0)
        assertEquals(0, s.detections.size)
    }

    @Test fun ignoresSlowPressureDriftWithoutMovement() {
        val s = Sim()
        s.run(15.0)
        s.run(2.0, dp = -0.035f, motion = 0.2f)  // weather-like change, no movement
        s.run(8.0)
        assertEquals(0, s.detections.size)
    }

    @Test fun ignoresRiseWhileAlreadyActive() {
        val s = Sim()
        s.run(15.0, motion = 3.0f)               // walking / busy hands, not sitting still
        s.run(2.0, dp = -0.035f, motion = 2.0f)
        s.run(8.0, motion = 3.0f)
        assertEquals(0, s.detections.size)
    }

    @Test fun cooldownPreventsRepeatPrompts() {
        val s = Sim()
        s.run(15.0); s.run(2.0, dp = -0.035f, motion = 2.0f); s.run(5.0)
        s.run(2.0, dp = 0.035f, motion = 2.0f)   // sits back down
        s.run(10.0)
        s.run(2.0, dp = -0.035f, motion = 2.0f)  // stands again 20 s later
        s.run(5.0)
        assertEquals(1, s.detections.size)
    }

    @Test fun detectsAgainAfterCooldown() {
        val s = Sim()
        s.run(15.0); s.run(2.0, dp = -0.035f, motion = 2.0f); s.run(5.0)
        s.run(2.0, dp = 0.035f, motion = 2.0f)
        s.run(100.0)                             // sits for well over 90 s
        s.run(2.0, dp = -0.035f, motion = 2.0f)
        s.run(5.0)
        assertEquals(2, s.detections.size)
    }
}
