package com.medicina.daedaluscompanion.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateMeasurementParserTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun uint8HeartRateWithRrIntervals() {
        // Flags 0x16: UINT8 HR, contact supported + detected, RR present.
        // HR 72, RR 1024 (= 1000 ms) and 819 (0x0333, ≈ 799.8 ms).
        val m = HeartRateMeasurementParser.parse(bytes(0x16, 72, 0x00, 0x04, 0x33, 0x03))!!

        assertEquals(72, m.hrBpm)
        assertEquals(true, m.sensorContact)
        assertNull(m.energyExpendedKj)
        assertEquals(2, m.rrIntervalsMs.size)
        assertEquals(1000f, m.rrIntervalsMs[0], 0.001f)
        assertEquals(819 * 1000f / 1024f, m.rrIntervalsMs[1], 0.001f)
    }

    @Test
    fun uint16HeartRateWithoutRr() {
        // Flags 0x01: UINT16 HR, contact not supported. HR 0x012C = 300.
        val m = HeartRateMeasurementParser.parse(bytes(0x01, 0x2C, 0x01))!!

        assertEquals(300, m.hrBpm)
        assertNull(m.sensorContact)
        assertEquals(emptyList<Float>(), m.rrIntervalsMs)
    }

    @Test
    fun energyExpendedIsSkippedBeforeRr() {
        // Flags 0x19: UINT16 HR, energy expended, RR present.
        // HR 80, energy 0x01F4 = 500 kJ, RR 512 (= 500 ms).
        val m = HeartRateMeasurementParser.parse(bytes(0x19, 80, 0x00, 0xF4, 0x01, 0x00, 0x02))!!

        assertEquals(80, m.hrBpm)
        assertEquals(500, m.energyExpendedKj)
        assertEquals(listOf(500f), m.rrIntervalsMs)
    }

    @Test
    fun contactSupportedButNotDetected() {
        val m = HeartRateMeasurementParser.parse(bytes(0x04, 60))!!
        assertEquals(false, m.sensorContact)
    }

    @Test
    fun truncatedPayloadReturnsNull() {
        assertNull(HeartRateMeasurementParser.parse(ByteArray(0)))
        assertNull(HeartRateMeasurementParser.parse(bytes(0x01, 0x2C)))
    }
}
