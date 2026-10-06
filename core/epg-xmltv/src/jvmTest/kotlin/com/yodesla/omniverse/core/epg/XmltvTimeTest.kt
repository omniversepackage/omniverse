package com.yodesla.omniverse.core.epg

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XmltvTimeTest {

    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0, s: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min, s).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun offsetPlusZero() {
        assertEquals(utc(2026, 9, 26, 12), parseXmltvTime("20260926120000 +0000"))
    }

    @Test
    fun offsetPlusTwoHours() {
        assertEquals(utc(2026, 9, 26, 10), parseXmltvTime("20260926120000 +0200"))
    }

    @Test
    fun offsetMinusFiveHours() {
        assertEquals(utc(2026, 9, 26, 17), parseXmltvTime("20260926120000 -0500"))
    }

    @Test
    fun noOffsetMeansUtc() {
        assertEquals(utc(2026, 9, 26, 12), parseXmltvTime("20260926120000"))
    }

    @Test
    fun noSpaceOffset() {
        assertEquals(utc(2026, 9, 26, 10), parseXmltvTime("20260926120000+0200"))
        assertEquals(utc(2026, 9, 26, 17), parseXmltvTime("20260926120000-0500"))
    }

    @Test
    fun minutesOnly() {
        assertEquals(utc(2026, 9, 26, 12), parseXmltvTime("202609261200"))
        assertEquals(utc(2026, 9, 26, 10), parseXmltvTime("202609261200 +0200"))
    }

    @Test
    fun leapDay() {
        assertEquals(utc(2028, 2, 29, 12), parseXmltvTime("20280229120000"))
        assertNull(parseXmltvTime("20270229120000"))
        assertEquals(utc(2000, 2, 29, 0), parseXmltvTime("20000229000000"))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(parseXmltvTime(""))
        assertNull(parseXmltvTime("not a date"))
        assertNull(parseXmltvTime("2026-09-26 12:00"))
        assertNull(parseXmltvTime("20261301120000"))
        assertNull(parseXmltvTime("20260001120000"))
        assertNull(parseXmltvTime("20260932120000"))
        assertNull(parseXmltvTime("20260926240000"))
        assertNull(parseXmltvTime("20260926126000"))
        assertNull(parseXmltvTime("20260926120060"))
        assertNull(parseXmltvTime("2026092612000"))
        assertNull(parseXmltvTime("202609261200000"))
        assertNull(parseXmltvTime("20260926120000 +25"))
        assertNull(parseXmltvTime("20260926120000 +2x00"))
    }
}
