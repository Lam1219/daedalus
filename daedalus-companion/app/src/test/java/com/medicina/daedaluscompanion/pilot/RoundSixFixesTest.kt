package com.medicina.daedaluscompanion.pilot

import com.medicina.shared.pilot.PilotSettings
import com.medicina.shared.pilot.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review round 006 (Android phone). */
class RoundSixFixesTest {
    private val saved = PilotSettings(false, "P05", 42, 20_000)

    // ---- A: earbuds are checked for one code and seed; the fields save only on confirm

    @Test
    fun a_earbudsCountOnlyForTheCodeAndSeedTheyWereCheckedFor() {
        val checked = saved.withEarbudsChecked(true)
        assertEquals("P05|42", checked.earbudsCheckedFor)
        assertTrue(checked.earbudsChecked)
        assertFalse("another code", checked.copy(participant = "P06").earbudsChecked)
        assertFalse("another seed", checked.copy(seed = 43).earbudsChecked)
        assertTrue("back to the same code and seed", checked.copy(participant = "P06").copy(participant = "P05").earbudsChecked)
        assertFalse("turned off on a fail", checked.withEarbudsChecked(false).earbudsChecked)
        assertFalse(PilotSettings().earbudsChecked)
    }

    @Test
    fun a_codeAndSeedSaveOnlyWhenValid() {
        assertNull("half-typed code", PilotPhoneRules.confirmIdentity(saved, "P0 ", "42"))
        assertNull("empty seed", PilotPhoneRules.confirmIdentity(saved, "P06", ""))
        assertNull("seed out of range", PilotPhoneRules.confirmIdentity(saved, "P06", "2147483648"))
        assertNull("no -1", PilotPhoneRules.confirmIdentity(saved, "P06", "-1"))
        val ok = PilotPhoneRules.confirmIdentity(saved.withEarbudsChecked(true), "P06", "7")!!
        assertEquals("P06", ok.participant)
        assertEquals(7L, ok.seed)
        assertFalse("the old check doesn't carry over", ok.earbudsChecked)
    }

    @Test
    fun a_theStatusLineSaysWhoTheEarbudsWereCheckedFor() {
        val line = { s: PilotSettings -> PilotPhoneRules.earbudsStatusLine(s, { "Earbuds checked for $it" }, { "Earbuds not checked" }) }
        assertEquals("Earbuds checked for P05", line(saved.withEarbudsChecked(true)))
        assertEquals("Earbuds not checked", line(saved))
        assertEquals("Earbuds not checked", line(saved.withEarbudsChecked(true).copy(seed = 1)))
    }

    @Test
    fun a_theCheckTravelsToTheWatchAndOldValuesDontCount() {
        val checked = saved.copy(enabled = true).withEarbudsChecked(true)
        assertEquals(checked, PilotSettings.decode(checked.encode()))
        assertTrue(PilotSettings.decode(checked.encode())!!.earbudsChecked)
        // A round 004/005 phone sent "1" for checked: not tied to anyone, so not checked.
        assertFalse(PilotSettings.decode("1|P05|42|20000|1|1")!!.earbudsChecked)
    }

    // ---- #5: an older watch rejects the settings, so it never turns study mode on

    @Test
    fun n5_studyOnIsSentAs2WhichOldWatchesReject() {
        val wire = saved.copy(enabled = true).encode()
        assertTrue(wire.startsWith("2|"))
        // The decode rule of watch builds before round 004 (and of rounds 004 and 005).
        fun oldDecodeAccepts(s: String) = s.split("|").let { it.size >= 5 && (it[0] == "0" || it[0] == "1") }
        assertFalse("old watch rejects study on", oldDecodeAccepts(wire))
        assertTrue("study off still reaches it", oldDecodeAccepts(saved.encode()))
        // New watches take 1 (older phones) and 2 as on.
        assertTrue(PilotSettings.decode(wire)!!.enabled)
        assertTrue(PilotSettings.decode("1|P05|42|20000|1")!!.enabled)
        assertNull(PilotSettings.decode("3|P05|42|20000|1"))
    }

    // ---- #6: refusal reasons

    @Test
    fun n6_refusalsKeepTheirReasons() {
        assertEquals("not_a_phone_player", PilotPhoneRules.refusal(Player.WATCH_AUDIO, saved.withEarbudsChecked(true)))
        assertEquals("not_a_phone_player", PilotPhoneRules.refusal(Player.WATCH_HAPTIC, saved))
        assertEquals("earbuds_not_checked", PilotPhoneRules.refusal(Player.PHONE_AUDIO, saved))
        assertNull(PilotPhoneRules.refusal(Player.PHONE_AUDIO, saved.withEarbudsChecked(true)))
        assertNull(PilotPhoneRules.refusal(Player.PHONE_VIBRATION, saved))
    }

    @Test
    fun n6_aRefusedStartStopsThePreviousBeatFirst() {
        val src = java.io.File("src/main/java/com/medicina/daedaluscompanion/pilot/PilotPhoneService.kt").readText()
        val start = src.substring(src.indexOf("private fun startBeat("))
        assertTrue("stopBeat before the refusal", start.indexOf("stopBeat(") in 0 until start.indexOf("PilotPhoneRules.refusal("))
    }
}
