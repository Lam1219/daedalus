package com.medicina.daedaluswatch.pilot

import com.medicina.shared.pilot.PilotSettings
import com.medicina.shared.pilot.Place
import com.medicina.shared.pilot.Player
import com.medicina.shared.pilot.PressPlanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round 008 (owner): Android earbuds are cut from the pilot. */
class RoundEightEarbudsCutTest {

    @Test
    fun noDrawYieldsEarbudsOnAndroidEvenWithEarbudsConnectedAndAnOldCheckStored() {
        // Study on, and a "checked" value from rounds 006-007 for exactly this code and seed.
        val s = PilotSettings(true, "P08", 123456789, 0, earbudsCheckedFor = "P08|123456789")
        for (day in -1L..22L) for (i in 0L until 200L)
            for (we in listOf(false, true)) for (pe in listOf(false, true)) for (pc in listOf(false, true)) {
                val plan = PressPlanner.plan(s, i, day, we, pe, pc)
                assertTrue("day $day press $i: $plan", plan.drawn != Place.EARBUDS)
                assertTrue(plan.player == Player.WATCH_HAPTIC || plan.player == Player.PHONE_VIBRATION)
                assertFalse(plan.earbudsConnected)
            }
    }

    @Test
    fun theWatchHasNoDeleteFromThePhoneSoUnsentPressesStayToBeSent() {
        val listener = File("src/main/java/com/medicina/daedaluswatch/sync/WatchMessageListener.kt").readText()
        assertFalse("no /pilot/delete handler", listener.contains("DELETE_PATH") || listener.contains("/pilot/delete"))
        val folder = File("src/main/java/com/medicina/daedaluswatch/pilot/PilotFolder.kt").readText()
        assertFalse("no folder wipe", folder.contains("fun deletePilotFiles"))
        // Unsent press files are still sent, and deleted only once the phone has confirmed them.
        val sync = File("src/main/java/com/medicina/daedaluswatch/sync/SessionSyncManager.kt").readText()
        assertTrue(sync.contains("it.name.startsWith(\"pilot_\")"))
        assertTrue(sync.contains("if (transferSuccess)"))
    }
}
