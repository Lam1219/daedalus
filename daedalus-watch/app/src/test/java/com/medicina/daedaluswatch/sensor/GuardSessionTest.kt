package com.medicina.daedaluswatch.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardSessionTest {

    private val columns = GuardSession.HEADER.trim().split(",")

    @Test fun modes() {
        assertTrue(GuardSession.isGuard("walk_guard"))
        assertTrue(GuardSession.isGuard("rest_guard"))
        assertFalse(GuardSession.isGuard("walking"))
        assertFalse(GuardSession.isGuard(null))
        assertEquals(GuardEngine.MODE_WALK, GuardSession.engineMode("walk_guard"))
        assertEquals(GuardEngine.MODE_REST, GuardSession.engineMode("rest_guard"))
    }

    @Test fun headerEndsWithTheEventColumns() {
        assertEquals(12, columns.size)
        assertEquals(listOf("event", "event_notes"), columns.takeLast(2))
        assertEquals("timestamp_ms", columns.first())
    }

    @Test fun sampleRowHasTwelveCellsAndEmptyEvents() {
        val cells = GuardSession.sampleRow(1000, 72f, 0.1f, 0.2f, 9.8f, 0.01f, 0.02f, 0.03f).trim().split(",")
        assertEquals(12, cells.size)
        assertEquals("1000", cells[0])
        assertEquals("0.03", cells[7])
        assertEquals(listOf("", "", "", ""), cells.takeLast(4))
    }

    @Test fun eventRowFillsOnlyTimeAndEvent() {
        val e = GuardSession.battery(5000, 87, false)
        val cells = GuardSession.eventRow(e).trim().split(",")
        assertEquals(12, cells.size)
        assertEquals("5000", cells[0])
        assertTrue(cells.subList(1, 10).all { it.isEmpty() })
        assertEquals("GUARD_BATTERY", cells[10])
        assertEquals("type=guard;battery_pct=87;charging=false", cells[11])
    }

    @Test fun guardRunRowsParseBackByHeaderName() {
        // A short synthetic rest guard: every row splits into the header's 12 columns.
        val t = LongArray(150) { it * 20L }
        val zeros = DoubleArray(150)
        val gravity = DoubleArray(150) { 9.81 }
        val events = GuardEngine.run(GuardEngine.MODE_REST, t, arrayOf(zeros, zeros, gravity), arrayOf(zeros, zeros, zeros))
        val rows = events.map { GuardSession.eventRow(it).trim().split(",") }
        assertEquals(listOf("GUARD_START", "GUARD_END"), rows.map { it[columns.indexOf("event")] })
        assertTrue(rows.all { it.size == columns.size })
        assertEquals("type=guard;mode=rest;windows=2;gaps=0", rows.last()[columns.indexOf("event_notes")])
    }

    @Test fun stuckCueRows() {
        assertEquals("type=manual;trigger=im_stuck;bpm=96", GuardSession.stuckCueStart(1, 96).notes)
        assertEquals("HAPTIC_GAIT_CUE_END", GuardSession.stuckCueEnd(2).name)
    }
}
