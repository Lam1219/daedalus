package com.medicina.daedaluscompanion.pilot

import com.medicina.shared.pilot.PilotSettings
import com.medicina.shared.pilot.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round 008 (owner): Android earbuds are cut from the pilot. */
class RoundEightEarbudsCutTest {
    /** A "checked" value stored by rounds 006-007 for exactly this code and seed. */
    private val oldChecked = PilotSettings(true, "P08", 42, 20_000, earbudsCheckedFor = "P08|42")

    @Test
    fun thePhoneNeverReportsEarbudsAndRefusesEarbudAudio() {
        assertFalse(PilotPhoneRules.earbudsForStatus(oldChecked, headphonesConnected = true))
        assertFalse(PilotPhoneRules.mayPlay(Player.PHONE_AUDIO, oldChecked))
        assertTrue("refused at once, so the wrist takes over", PilotPhoneRules.refusal(Player.PHONE_AUDIO, oldChecked) != null)
        assertNull("the pocket still plays", PilotPhoneRules.refusal(Player.PHONE_VIBRATION, oldChecked))
    }

    @Test
    fun deletePilotFilesDoesntTouchTheWatchAndTheScreenHasNoEarbudsSwitch() {
        val screen = File("src/main/java/com/medicina/daedaluscompanion/ui/screens/PilotScreen.kt").readText()
        assertFalse(screen.contains("sendPilotDelete"))
        assertFalse(screen.contains("pilot_earbuds_checked"))
        val client = File("src/main/java/com/medicina/daedaluscompanion/wear/WatchDataListener.kt").readText()
        assertFalse(client.contains("DELETE_PATH"))
    }
}
