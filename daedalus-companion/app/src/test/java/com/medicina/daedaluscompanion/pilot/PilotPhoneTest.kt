package com.medicina.daedaluscompanion.pilot

import com.medicina.shared.pilot.Pilot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's "carried" and "moved" signals for the home pilot. */
class PilotPhoneTest {

    @Test
    fun theFirstCounterEventIsTheTotalNotAStep() {
        val s = StepTracker()
        s.onCounter(1_000, 5_000f)
        assertNull("registering reports the running total only", s.lastStepMs)
        s.onCounter(2_000, 5_000f)
        assertNull(s.lastStepMs)
        s.onCounter(3_000, 5_003f)
        assertEquals(3_000L, s.lastStepMs)
        assertEquals(57_000L, s.agoMs(60_000))
        // Carried means a step in the last minute (the watch applies the window).
        assertTrue(s.agoMs(63_000)!! <= Pilot.PHONE_CARRIED_WINDOW_S * 1000L)
        assertFalse(s.agoMs(63_001)!! <= Pilot.PHONE_CARRIED_WINDOW_S * 1000L)
    }

    @Test
    fun stepDetectorEventsAreSteps() {
        val s = StepTracker()
        s.onStep(10)
        s.onStep(5) // batched out of order: keeps the newest
        assertEquals(10L, s.lastStepMs)
    }

    @Test
    fun aPhoneOnATableDidNotMoveAndStandingUpWithItInAPocketDid() {
        val table = MovementWindow()
        for (i in 0 until 600) table.add(i * 100L, 0.01f * (i % 3), 0.02f, 9.81f - 0.01f * (i % 2))
        assertEquals(false, table.moved(60_000))

        val pocket = MovementWindow()
        // Sitting with the phone flat in the pocket, then standing: the phone turns upright.
        for (i in 0 until 500) pocket.add(i * 100L, 0.1f, 0.3f, 9.8f)
        for (i in 500 until 600) pocket.add(i * 100L, 0.3f, 9.6f, 1.5f)
        assertEquals(true, pocket.moved(60_000))
    }

    @Test
    fun oldMovementDoesNotCountAndNoSamplesIsUnknown() {
        val w = MovementWindow()
        assertNull(w.moved(0))
        w.add(0, 0f, 0f, 9.8f)
        w.add(100, 9.8f, 0f, 0f)
        for (i in 1 until 700) w.add(100 + i * 100L, 9.8f, 0f, 0f)
        assertEquals("the tilt was over a minute ago", false, w.moved(70_000))
    }
}
