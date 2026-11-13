package com.medicina.daedaluswatch.pilot

import com.medicina.daedaluswatch.haptics.HapticService
import com.medicina.daedaluswatch.sensor.GuardSession
import org.junit.Assert.assertEquals
import org.junit.Test

/** B6: in study mode a cue stops by itself after 10 minutes; outside it the hour stays. */
class StudyLimitTest {
    @Test
    fun studyModeCapsCuesAtTenMinutesAndChangesNothingElse() {
        for (mode in listOf("walking", "rest", "writing", "stress")) {
            assertEquals(10 * 60 * 1000L, HapticService.sessionLimitMs(study = true, mode = mode))
            assertEquals(60 * 60 * 1000L, HapticService.sessionLimitMs(study = false, mode = mode))
        }
        for (mode in listOf(GuardSession.MODE_WALK_GUARD, GuardSession.MODE_REST_GUARD)) {
            assertEquals(60 * 60 * 1000L, HapticService.sessionLimitMs(study = true, mode = mode))
        }
    }
}
