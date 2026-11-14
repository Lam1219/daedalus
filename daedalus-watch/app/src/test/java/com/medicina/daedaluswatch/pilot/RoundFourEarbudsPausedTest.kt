package com.medicina.daedaluswatch.pilot

import com.medicina.daedaluswatch.sensor.WatchBootReceiver
import com.medicina.shared.pilot.Pilot
import com.medicina.shared.pilot.PilotSettings
import com.medicina.shared.pilot.Place
import com.medicina.shared.pilot.Player
import com.medicina.shared.pilot.PressPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Round 004 (owner, 2026-11-13): Android earbuds paused behind "Earbuds checked for this participant". */
class RoundFourEarbudsPausedTest {

    private val off = PilotSettings(true, "P04", 123456789, 0)
    private val on = off.withEarbudsChecked(true)

    @Test
    fun theSettingIsOffByDefaultAndTravelsToTheWatch() {
        assertFalse(PilotSettings().earbudsChecked)
        assertFalse(off.earbudsChecked)
        assertEquals(on, PilotSettings.decode(on.encode()))
        assertEquals(off, PilotSettings.decode(off.encode()))
        // A phone on an older build sends five fields: not checked.
        assertFalse(PilotSettings.decode("1|P04|5|20000|1")!!.earbudsChecked)
    }

    @Test
    fun withTheSettingOffNoDrawEverYieldsEarbudsEvenWithEarbudsConnected() {
        for (day in -1L..22L) for (i in 0L until 200L)
            for (we in listOf(false, true)) for (pe in listOf(false, true)) for (pc in listOf(false, true)) {
                val plan = PressPlanner.plan(off, i, day, we, pe, pc)
                assertTrue("day $day press $i: $plan", plan.drawn != Place.EARBUDS)
                assertTrue(plan.player != Player.WATCH_AUDIO && plan.player != Player.PHONE_AUDIO)
                assertFalse("earbuds count as not connected", plan.earbudsConnected)
            }
    }

    @Test
    fun withTheSettingOnDrawsAreAsBefore() {
        var earbudsDrawn = 0
        for (day in -1L..22L) for (i in 0L until 200L)
            for (we in listOf(false, true)) for (pe in listOf(false, true)) for (pc in listOf(false, true)) {
                val plan = PressPlanner.plan(on, i, day, we, pe, pc)
                assertEquals(Pilot.choosePlace(on.seed, i, day, we || pe, pc), plan.drawn)
                assertEquals(we || pe, plan.earbudsConnected)
                if (plan.drawn == Place.EARBUDS) {
                    earbudsDrawn++
                    assertEquals(if (we) Player.WATCH_AUDIO else Player.PHONE_AUDIO, plan.player)
                }
            }
        assertTrue(earbudsDrawn > 0)
    }

    @Test
    fun withNoPilotFolderABootTouchesNothingPilotRelated() {
        val calls = mutableListOf<String>()
        WatchBootReceiver.onBoot("android.intent.action.BOOT_COMPLETED", pilotActive = false, folderExists = false, { calls += "restart" }, { calls += "recover" })
        assertEquals("no PilotPresses, no service", emptyList<String>(), calls)
        WatchBootReceiver.onBoot("android.intent.action.BOOT_COMPLETED", pilotActive = false, folderExists = true, { calls += "restart" }, { calls += "recover" })
        assertEquals(listOf("recover"), calls)
    }

    @Test
    fun theFolderCheckIsTheFolderOnly() {
        val files = Files.createTempDirectory("files").toFile()
        assertFalse(pilotFolderExists(files))
        File(files, "pilot").writeText("not a folder")
        assertFalse(pilotFolderExists(files))
        File(files, "pilot").delete(); File(files, "pilot").mkdir()
        assertTrue(pilotFolderExists(files))
        files.deleteRecursively()
    }
}
