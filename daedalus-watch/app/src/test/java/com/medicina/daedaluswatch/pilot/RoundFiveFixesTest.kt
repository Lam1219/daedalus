package com.medicina.daedaluswatch.pilot

import com.medicina.daedaluswatch.sensor.WatchBootReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Review round 005 (Android watch). */
class RoundFiveFixesTest {

    // ---- #6: a press that begins while the screen's flag is stale still shows its check-in

    @Test
    fun n6_aPressMakesTheScreenReadThePresses() {
        PilotFlags.pressStarted = false
        assertFalse(PilotFlags.screenReadsPresses(pilotOn = false))
        PilotFlags.pressStarted = true // what PilotPresses.begin sets
        assertTrue(PilotFlags.screenReadsPresses(pilotOn = false))
        PilotFlags.pressStarted = false
        assertTrue(PilotFlags.screenReadsPresses(pilotOn = true))
    }

    @Test
    fun n6_beginSetsTheFlag() {
        val src = File("src/main/java/com/medicina/daedaluswatch/pilot/PilotPresses.kt").readText()
        val begin = src.substring(src.indexOf("fun begin("), src.indexOf("fun onBeatStopped("))
        assertTrue(begin.contains("PilotFlags.pressStarted = true"))
    }

    // ---- #7: study on with no pilot folder yet: restart, nothing to recover

    @Test
    fun n7_bootWithTheStudyOnAndNoFolderRestartsWithoutRecovery() {
        val calls = mutableListOf<String>()
        WatchBootReceiver.onBoot("android.intent.action.BOOT_COMPLETED", pilotActive = true, folderExists = false, { calls += "restart" }, { calls += "recover" })
        assertEquals(listOf("restart"), calls)
    }

    // ---- #5: both apps carry the same visible pilot build, pilot5 or later

    @Test
    fun n5_phoneAndWatchShowTheSamePilotBuild() {
        fun build(path: String) = Regex("versionName = \"0\\.1\\.0-pilot(\\d+)\"").find(File(path).readText())?.groupValues?.get(1)?.toInt()
        val watch = build("build.gradle.kts")
        val phone = build("../../daedalus-companion/app/build.gradle.kts")
        assertTrue("watch shows a pilot build", watch != null && watch >= 5)
        assertEquals(watch, phone)
    }
}
