package com.medicina.daedaluscompanion.pilot

import com.medicina.shared.pilot.PilotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Review round 007 (Android phone). */
class RoundSevenFixesTest {
    private val on = PilotSettings(true, "P07", 42, 20_000).withEarbudsChecked(true)

    /** How watch builds before round 006 handled a settings message: (accepted, enabled). */
    private fun oldWatch(message: String): Pair<Boolean, Boolean> {
        val p = message.split("|")
        if (p.size < 5 || (p[0] != "0" && p[0] != "1")) return false to false
        return true to (p[0] == "1")
    }

    // ---- #1: off before on, so an older watch can't stay in study mode

    @Test
    fun n1_anOlderWatchThatWasOnEndsUpOff() {
        var watchOn = true // it was already in study mode
        for (m in on.wireMessages()) {
            val (accepted, enabled) = oldWatch(m)
            if (accepted) watchOn = enabled
        }
        assertFalse("the older watch is off", watchOn)
    }

    @Test
    fun n1_aCurrentWatchIgnoresThePrecursorAndEndsUpOn() {
        val messages = on.wireMessages()
        assertEquals(2, messages.size)
        assertTrue(messages[0].startsWith("0|") && PilotSettings.isPrecursor(messages[0]))
        assertFalse(PilotSettings.isPrecursor(messages[1]))
        val applied = messages.filterNot { PilotSettings.isPrecursor(it) }.map { PilotSettings.decode(it)!! }
        assertEquals(listOf(on), applied)
        // Off is sent alone.
        assertEquals(listOf(on.copy(enabled = false).encode()), on.copy(enabled = false).wireMessages())
    }

    @Test
    fun n1_thePhoneSendsEveryMessageInOrder() {
        val src = File("src/main/java/com/medicina/daedaluscompanion/wear/WatchDataListener.kt").readText()
        val send = src.substring(src.indexOf("suspend fun sendPilotSettings"), src.indexOf("suspend fun sendPilotDelete"))
        assertTrue(send.contains("settings.wireMessages()"))
        assertTrue("awaited, so the off arrives first", send.contains(".await()"))
    }

    // ---- #4: "Delete pilot files" also deletes the watch's copies

    @Test
    fun n4_deleteReachesTheWatch() {
        val screen = File("src/main/java/com/medicina/daedaluscompanion/ui/screens/PilotScreen.kt").readText()
        assertTrue(screen.contains("sendPilotDelete()"))
    }

    // ---- #5: the seed compares as a number

    @Test
    fun n5_leadingZerosCountAsSaved() {
        assertFalse(PilotPhoneRules.isUnsaved(on, "P07", "042"))
        assertFalse(PilotPhoneRules.isUnsaved(on, "P07", "42"))
        assertTrue(PilotPhoneRules.isUnsaved(on, "P07", "43"))
        assertTrue(PilotPhoneRules.isUnsaved(on, "P07", ""))
        assertTrue(PilotPhoneRules.isUnsaved(on, "P08", "42"))
    }
}
