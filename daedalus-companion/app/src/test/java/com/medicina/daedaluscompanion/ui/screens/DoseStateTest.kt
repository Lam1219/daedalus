package com.medicina.daedaluscompanion.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class DoseStateTest {
    private val at = 1_000_000_000L
    private val minute = 60_000L

    @Test fun beforeItsTimeIsUpcoming() = assertEquals(DoseState.UPCOMING, doseState(at - minute, at, taken = false))
    @Test fun atItsTimeIsDue() = assertEquals(DoseState.DUE, doseState(at, at, taken = false))
    @Test fun stillDueJustUnderAnHourLater() = assertEquals(DoseState.DUE, doseState(at + 59 * minute, at, taken = false))
    @Test fun missedAnHourLater() = assertEquals(DoseState.MISSED, doseState(at + 60 * minute, at, taken = false))
    @Test fun takenWinsOverMissed() = assertEquals(DoseState.TAKEN, doseState(at + 3 * 60 * minute, at, taken = true))
}
