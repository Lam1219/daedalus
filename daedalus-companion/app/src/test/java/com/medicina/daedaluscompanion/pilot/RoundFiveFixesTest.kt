package com.medicina.daedaluscompanion.pilot

import com.medicina.shared.pilot.PilotSettings
import com.medicina.shared.pilot.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review round 005 (Android phone). */
class RoundFiveFixesTest {
    // A "checked" value stored by rounds 006-007; since round 008 nothing reads it as on.
    private val checked = PilotSettings(false, "P05", 42, 20_000, earbudsCheckedFor = "P05|42")

    // #4 (clear on edit) was replaced in round 006 by a check tied to the code and seed: RoundSixFixesTest.

    // ---- #5 / #7: the phone's own guards

    @Test
    fun n5_thePhoneRefusesEarbudAudioUntilChecked() {
        val notChecked = PilotSettings(false, "P05", 42, 20_000)
        assertFalse(PilotPhoneRules.mayPlay(Player.PHONE_AUDIO, notChecked))
        assertFalse("round 008: never, even with an old check stored", PilotPhoneRules.mayPlay(Player.PHONE_AUDIO, checked))
        assertTrue("the pocket isn't paused", PilotPhoneRules.mayPlay(Player.PHONE_VIBRATION, notChecked))
        assertFalse("watch players never play on the phone", PilotPhoneRules.mayPlay(Player.WATCH_AUDIO, checked))
    }

    @Test
    fun n7_earbudsStatusNeedsTheCheckAndHeadphones() {
        assertFalse(PilotPhoneRules.earbudsForStatus(PilotSettings(false, "P05", 42, 20_000), headphonesConnected = true))
        assertFalse(PilotPhoneRules.earbudsForStatus(checked, headphonesConnected = false))
        assertFalse("round 008: never", PilotPhoneRules.earbudsForStatus(checked, headphonesConnected = true))
    }
}
