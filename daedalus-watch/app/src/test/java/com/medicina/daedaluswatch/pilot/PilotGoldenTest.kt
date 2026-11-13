package com.medicina.daedaluswatch.pilot

import com.medicina.daedaluswatch.sensor.Json
import com.medicina.shared.pilot.CheckinDay
import com.medicina.shared.pilot.Pilot
import com.medicina.shared.pilot.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The home pilot rules (com.medicina.shared.pilot.Pilot) against the Python reference's golden
 * vectors, daedalus_watch_kit/golden/pilot/pilot.json (pilot-1.0). Every row must match exactly.
 */
class PilotGoldenTest {

    @Suppress("UNCHECKED_CAST")
    private val golden = Json.parse(File("../../daedalus_watch_kit/golden/pilot/pilot.json").readText()) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun rows(key: String) = golden[key] as List<Map<String, Any?>>
    private fun long(x: Any?) = (x as Double).toLong()
    private fun hex(x: Any?) = (x as String).toULong(16)

    @Test
    fun versionAndConstants() {
        assertEquals(Pilot.VERSION, golden["version"])
        assertEquals(Pilot.PLACES.map { it.wire }, golden["places_order"])
        @Suppress("UNCHECKED_CAST")
        val c = golden["constants"] as Map<String, Any?>
        assertEquals(Pilot.LEARNING_DAYS.toLong(), long(c["learning_days"]))
        assertEquals(Pilot.PILOT_DAYS.toLong(), long(c["pilot_days"]))
        assertEquals(Pilot.MAX_SEED, long(c["max_seed"]))
        assertEquals(Pilot.MAX_CHECKINS_PER_DAY.toLong(), long(c["max_checkins_per_day"]))
        assertEquals(Pilot.REMINDER_GAP_MS, long(c["reminder_gap_ms"]))
        assertEquals(Pilot.PHONE_CARRIED_WINDOW_S.toLong(), long(c["phone_carried_window_s"]))
        assertEquals(Pilot.STUDY_MAX_CUE_S.toLong(), long(c["study_max_cue_s"]))
        assertEquals(Pilot.BUFFER_BEFORE_S.toLong(), long(c["buffer_before_s"]))
        assertEquals(Pilot.BUFFER_AFTER_S.toLong(), long(c["buffer_after_s"]))
    }

    @Test
    fun splitmix64() {
        val r = rows("splitmix64")
        assertTrue(r.isNotEmpty())
        for (row in r) assertEquals(row.toString(), hex(row["output"]), Pilot.splitmix64(hex(row["input"])))
    }

    @Test
    fun draws() {
        val r = rows("draws")
        assertEquals(25, r.size)
        for (row in r) assertEquals(row.toString(), hex(row["draw"]), Pilot.draw(long(row["seed"]), long(row["press_index"])))
    }

    @Test
    fun choosePlaceAll1008Rows() {
        val r = rows("choose_place")
        assertEquals(1008, r.size)
        var mismatches = 0
        for (row in r) {
            val got = Pilot.choosePlace(
                long(row["seed"]), long(row["press_index"]), long(row["day_index"]),
                row["earbuds_connected"] as Boolean, row["phone_carried"] as Boolean,
            )
            if (got.wire != row["place"]) mismatches++
        }
        assertEquals("choose_place rows that differ from Python", 0, mismatches)
    }

    @Test
    fun shouldRemindPocket() {
        val r = rows("should_remind_pocket")
        assertTrue(r.size > 100)
        for (row in r) {
            val got = Pilot.shouldRemindPocket(
                long(row["now_ms"]), row["stood_up"] as Boolean, long(row["phone_steps_last_min"]).toInt(),
                row["phone_moved_last_min"] as Boolean, row["last_reminder_ms"]?.let { long(it) },
                row["beat_playing"] as Boolean, row["enabled"] as Boolean,
            )
            assertEquals(row.toString(), row["remind"], got)
        }
    }

    @Test
    fun shouldAskCheckin() {
        for (row in rows("should_ask_checkin")) {
            assertEquals(row.toString(), row["ask"], Pilot.shouldAskCheckin(long(row["asked_today"]).toInt()))
        }
    }

    // ---- Beyond the vectors: the plan's B3 "done when" and the B5 daily limit.

    @Test
    fun overThousandsOfPressesEachAvailablePlaceGetsAnEqualShareWithin3Percent() {
        for (avail in listOf(true to true, true to false, false to true)) {
            val counts = HashMap<Place, Int>()
            val n = 3000L
            for (i in 0 until n) {
                val p = Pilot.choosePlace(123456789, i, 10, avail.first, avail.second)
                counts[p] = (counts[p] ?: 0) + 1
            }
            val places = Pilot.availablePlaces(avail.first, avail.second)
            assertEquals("never an unavailable place", places.toSet(), counts.keys)
            for (p in places) {
                val share = counts[p]!!.toDouble() / n
                assertEquals("$p share with $avail", 1.0 / places.size, share, 0.03)
            }
        }
    }

    @Test
    fun anUnavailablePlaceIsNeverDrawnOnAnyDay() {
        for (day in -3L..25L) for (i in 0L until 200L) {
            assertEquals(Place.WRIST, Pilot.choosePlace(42, i, day, earbudsConnected = false, phoneCarried = false))
            assertTrue(Pilot.choosePlace(42, i, day, earbudsConnected = true, phoneCarried = false) != Place.POCKET)
            assertTrue(Pilot.choosePlace(42, i, day, earbudsConnected = false, phoneCarried = true) != Place.EARBUDS)
        }
    }

    @Test
    fun dayIndexCountsLocalCalendarDays() {
        val enrol = LocalDate.of(2026, 11, 20)
        assertEquals(0, Pilot.dayIndex(enrol, enrol))
        assertEquals(-1, Pilot.dayIndex(enrol, LocalDate.of(2026, 11, 19)))
        assertEquals(7, Pilot.dayIndex(enrol, LocalDate.of(2026, 11, 27)))
        assertEquals(21, Pilot.dayIndex(enrol, LocalDate.of(2026, 12, 11)))
    }

    @Test
    fun theSeventhPressOfADayIsNotAskedAndTheCountResetsNextDay() {
        val today = LocalDate.of(2026, 11, 25)
        var d = CheckinDay(null, 0)
        repeat(6) {
            assertTrue("press ${it + 1} is asked", d.shouldAsk(today))
            d = d.afterAsking(today)
        }
        assertFalse("7th press of the day isn't asked", d.shouldAsk(today))
        assertTrue("a new day asks again", d.shouldAsk(today.plusDays(1)))
        assertEquals(1, d.afterAsking(today.plusDays(1)).asked)
    }
}
