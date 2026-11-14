package com.medicina.daedaluswatch.pilot

import com.medicina.shared.pilot.PilotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    @Test
    fun n4_theWatchDeletesItsPilotFilesOnRequest() {
        val files = Files.createTempDirectory("files").toFile()
        val pilot = File(files, "pilot").apply { mkdirs() }
        listOf("pilot_CHECK_00000_1.json", "pending_pilot_CHECK_00001_2.txt", "battery_CHECK_20261113.csv").forEach { File(pilot, it).writeText("x") }
        File(files, "other.txt").writeText("keep")
        assertEquals(3, deletePilotFiles(files))
        assertTrue(pilot.listFiles()!!.isEmpty())
        assertTrue("nothing outside the pilot folder", File(files, "other.txt").exists())
        assertEquals(0, deletePilotFiles(Files.createTempDirectory("empty").toFile()))
        files.deleteRecursively()
        val src = File("src/main/java/com/medicina/daedaluswatch/sync/WatchMessageListener.kt").readText()
        assertTrue(src.contains("PilotLink.DELETE_PATH ->"))
    }
}
