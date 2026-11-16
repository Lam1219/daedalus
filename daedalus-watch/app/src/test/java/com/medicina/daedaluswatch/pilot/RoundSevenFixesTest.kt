package com.medicina.daedaluswatch.pilot

import com.medicina.shared.pilot.PilotSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Review round 007 (Android watch). */
class RoundSevenFixesTest {

    @Test
    fun n1_theWatchIgnoresThePrecursorOff() {
        val src = File("src/main/java/com/medicina/daedaluswatch/sync/WatchMessageListener.kt").readText()
        val settings = src.substring(src.indexOf("PilotLink.SETTINGS_PATH ->"))
        assertTrue("checked before decoding", settings.indexOf("isPrecursor(") in 0 until settings.indexOf("PilotSettings.decode("))
        val on = PilotSettings(true, "P07", 42, 20_000)
        assertTrue(PilotSettings.isPrecursor(on.wireMessages().first()))
        assertFalse(PilotSettings.isPrecursor(on.encode()))
    }

    // n4_theWatchDeletesItsPilotFilesOnRequest: removed in round 008 (the watch-side delete was removed).

}
