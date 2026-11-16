package com.medicina.daedaluscompanion.pilot

import com.medicina.shared.pilot.PilotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Review round 009 (Android phone). */
class RoundNineFixesTest {

    // ---- #6: field 6 always goes out empty, whatever is stored

    @Test
    fun n6_anOldCheckedValueIsNeverSent() {
        val old = PilotSettings(true, "P05", 42, 20_000, earbudsCheckedFor = "P05|42")
        for (message in old.wireMessages()) {
            val fields = message.split("|")
            assertEquals("field 6 empty in $message", "", fields[5])
        }
        assertEquals("same field count as before", 6, old.encode().split("|").size)
        assertEquals("", PilotSettings.decode(old.encode())!!.earbudsCheckedFor)
    }

    // ---- #3: CHECK is reserved

    @Test
    fun n3_checkIsNotAParticipantCode() {
        for (c in listOf("CHECK", "check", "Check", "cHeCk")) assertFalse(c, PilotSettings.isValidCode(c))
        assertTrue(PilotSettings.isValidCode("P05"))
        assertTrue("only the exact code is reserved", PilotSettings.isValidCode("CHECK1"))
    }

    // ---- #5: the delete confirmation says phone only, and that the watch may still send

    @Test
    fun n5_deleteConfirmationSaysPhoneOnly() {
        fun confirm(path: String) = Regex("<string name=\"pilot_delete_confirm\">([^<]*)</string>").find(File(path).readText())!!.groupValues[1]
        val en = confirm("src/main/res/values/strings.xml")
        val fr = confirm("src/main/res/values-fr/strings.xml")
        assertTrue(en, en.contains("this phone only") && en.contains("watch"))
        assertTrue(fr, fr.contains("ce téléphone seulement") && fr.contains("montre"))
    }
}
