package com.medicina.daedaluscompanion.pilot

import com.medicina.shared.pilot.PilotSettings
import com.medicina.shared.pilot.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review round 005 (Android phone). */
class RoundFiveFixesTest {
    private val checked = PilotSettings(false, "P05", 42, 20_000).withEarbudsChecked(true)

    // #4 (clear on edit) was replaced in round 006 by a check tied to the code and seed: RoundSixFixesTest.

    // ---- #5 / #7: the phone's own guards

    @Test
    fun n5_thePhoneRefusesEarbudAudioUntilChecked() {
        val notChecked = checked.withEarbudsChecked(false)
        assertFalse(PilotPhoneRules.mayPlay(Player.PHONE_AUDIO, notChecked))
        assertTrue(PilotPhoneRules.mayPlay(Player.PHONE_AUDIO, checked))
        assertTrue("the pocket isn't paused", PilotPhoneRules.mayPlay(Player.PHONE_VIBRATION, notChecked))
        assertFalse("watch players never play on the phone", PilotPhoneRules.mayPlay(Player.WATCH_AUDIO, checked))
    }

    @Test
    fun n7_earbudsStatusNeedsTheCheckAndHeadphones() {
        assertFalse(PilotPhoneRules.earbudsForStatus(checked.withEarbudsChecked(false), headphonesConnected = true))
        assertFalse(PilotPhoneRules.earbudsForStatus(checked, headphonesConnected = false))
        assertTrue(PilotPhoneRules.earbudsForStatus(checked, headphonesConnected = true))
    }
}
