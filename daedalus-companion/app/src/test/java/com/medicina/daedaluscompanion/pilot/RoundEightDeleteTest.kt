package com.medicina.daedaluscompanion.pilot

import com.medicina.shared.pilot.PilotSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round 008: "Delete pilot files" is the phone's only, and only while study mode is off. */
class RoundEightDeleteTest {
    @Test
    fun deleteIsUnavailableWithStudyModeOn() {
        assertFalse(PilotPhoneRules.canDeletePilotFiles(PilotSettings(true, "P08", 1, 0)))
        assertTrue(PilotPhoneRules.canDeletePilotFiles(PilotSettings(false, "P08", 1, 0)))
        val screen = File("src/main/java/com/medicina/daedaluscompanion/ui/screens/PilotScreen.kt").readText()
        assertTrue("the button follows the rule", screen.contains("enabled = canDelete"))
    }
}
