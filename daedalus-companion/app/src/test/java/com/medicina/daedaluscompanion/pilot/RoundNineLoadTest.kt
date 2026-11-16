package com.medicina.daedaluscompanion.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review round 009, #6: a stored earbuds check is cleared when the phone loads its settings. */
class RoundNineLoadTest {
    @Test
    fun n6_aStoredCheckLoadsAsNotChecked() {
        val loaded = PilotPrefs.fromStored("2|P05|42|20000|1|P05:42")
        assertEquals("", loaded.earbudsCheckedFor)
        assertEquals("P05", loaded.participant)
        assertTrue(loaded.enabled)
        assertEquals("", PilotPrefs.fromStored(null).earbudsCheckedFor)
    }
}
