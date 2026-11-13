package com.medicina.daedaluscompanion.pilot

import com.medicina.shared.pilot.PilotSettings
import com.medicina.shared.pilot.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review round 005 (Android phone). */
class RoundFiveFixesTest {
    private val checked = PilotSettings(false, "P05", 42, 20_000, earbudsChecked = true)

    // ---- #4: "Earbuds checked" doesn't outlive a participant

    @Test
    fun n4_aNewCodeOrSeedTurnsEarbudsCheckedOff() {
        assertFalse(checked.withIdentity("P06", 42).earbudsChecked)
        assertFalse(checked.withIdentity("P05", 43).earbudsChecked)
        assertEquals("P06", checked.withIdentity("P06", 42).participant)
        assertEquals(43L, checked.withIdentity("P05", 43).seed)
        assertTrue("same participant keeps it", checked.withIdentity("P05", 42).earbudsChecked)
        assertEquals(checked, checked.withIdentity("P05", 42))
    }

    // ---- #5 / #7: the phone's own guards

    @Test
    fun n5_thePhoneRefusesEarbudAudioUntilChecked() {
        val notChecked = checked.copy(earbudsChecked = false)
        assertFalse(PilotPhoneRules.mayPlay(Player.PHONE_AUDIO, notChecked))
        assertTrue(PilotPhoneRules.mayPlay(Player.PHONE_AUDIO, checked))
        assertTrue("the pocket isn't paused", PilotPhoneRules.mayPlay(Player.PHONE_VIBRATION, notChecked))
        assertFalse("watch players never play on the phone", PilotPhoneRules.mayPlay(Player.WATCH_AUDIO, checked))
    }

    @Test
    fun n7_earbudsStatusNeedsTheCheckAndHeadphones() {
        assertFalse(PilotPhoneRules.earbudsForStatus(checked.copy(earbudsChecked = false), headphonesConnected = true))
        assertFalse(PilotPhoneRules.earbudsForStatus(checked, headphonesConnected = false))
        assertTrue(PilotPhoneRules.earbudsForStatus(checked, headphonesConnected = true))
    }
}
