package com.medicina.daedaluscompanion.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class MedRemindersTest {
    private val toronto = ZoneId.of("America/Toronto")

    private fun at(text: String) = ZonedDateTime.of(LocalDateTime.parse(text), toronto).toInstant().toEpochMilli()
    private fun local(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(toronto).toLocalDateTime().toString()

    @Test
    fun laterTodayStaysToday() {
        assertEquals("2026-10-22T20:00", local(MedReminders.nextTime(20, 0, at("2026-10-22T08:30"), toronto)))
    }

    @Test
    fun pastTimeMovesToTomorrow() {
        assertEquals("2026-10-23T08:00", local(MedReminders.nextTime(8, 0, at("2026-10-22T08:00"), toronto)))
    }

    @Test
    fun keepsClockTimeWhenClocksFallBack() {
        // 1 November 2026: clocks go back an hour overnight; the day is 25 hours long.
        assertEquals("2026-11-01T08:00", local(MedReminders.nextTime(8, 0, at("2026-10-31T09:00"), toronto)))
    }

    @Test
    fun keepsClockTimeWhenClocksSpringForward() {
        // 8 March 2026: the day is 23 hours long.
        assertEquals("2026-03-08T08:00", local(MedReminders.nextTime(8, 0, at("2026-03-07T09:00"), toronto)))
    }

    @Test
    fun skippedTimeMovesPastTheGap() {
        // 2:30 doesn't exist on 8 March 2026 in Toronto.
        assertEquals("2026-03-08T03:30", local(MedReminders.nextTime(2, 30, at("2026-03-07T09:00"), toronto)))
    }
}
