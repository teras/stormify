// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlin.time.ExperimentalTime::class)

package test

import onl.ycode.kdbc.TypeConversion
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies the direct pair-wise temporal converters added on top of the
 * epoch-pivot registry. The direct path short-circuits the timezone + noon
 * hack machinery for pure-decomposed pairs, so it must be DST-immune by
 * construction and must preserve the exact decomposed value when formatting
 * to String.
 */
open class TemporalConversionTest {

    // --- Direct pure-decomposed pairs --------------------------------------

    @Test
    fun localDateToLocalDateTime() {
        val date = LocalDate(2026, 3, 20)
        val dt = TypeConversion.castScalar(LocalDateTime::class, date)
        assertNotNull(dt)
        assertEquals(LocalDateTime(2026, 3, 20, 0, 0, 0, 0), dt)
    }

    @Test
    fun localDateTimeToLocalDate() {
        val dt = LocalDateTime(2026, 3, 20, 14, 30, 45, 0)
        val date = TypeConversion.castScalar(LocalDate::class, dt)
        assertEquals(LocalDate(2026, 3, 20), date)
    }

    @Test
    fun localDateTimeToLocalTime() {
        val dt = LocalDateTime(2026, 3, 20, 14, 30, 45, 0)
        val time = TypeConversion.castScalar(LocalTime::class, dt)
        assertEquals(LocalTime(14, 30, 45, 0), time)
    }

    @Test
    fun localTimeToLocalDateTime() {
        val time = LocalTime(14, 30, 45, 0)
        val dt = TypeConversion.castScalar(LocalDateTime::class, time)
        // EPOCH-anchored: preserves the time-of-day with a deterministic date.
        assertEquals(LocalDateTime(1970, 1, 1, 14, 30, 45, 0), dt)
    }

    // --- String formatting preserves the exact decomposed value ------------

    @Test
    fun localDateToStringIsIsoDate() {
        val date = LocalDate(2026, 3, 20)
        val s = TypeConversion.castScalar(String::class, date)
        assertEquals("2026-03-20", s)
    }

    @Test
    fun localDateTimeToStringPreservesHourMinute() {
        val dt = LocalDateTime(2026, 3, 20, 14, 30, 45, 0)
        val s = TypeConversion.castScalar(String::class, dt)
        assertEquals("2026-03-20T14:30:45", s)
    }

    @Test
    fun localTimeToStringIsIsoTime() {
        val time = LocalTime(14, 30, 45, 0)
        val s = TypeConversion.castScalar(String::class, time)
        assertEquals("14:30:45", s)
    }

    // --- DST-boundary round trip --------------------------------------------
    //
    // Brazil observed DST on 2000-03-19 with a forward jump at midnight, so
    // 00:00 local time didn't exist on that day. The old epoch-pivot path
    // avoided the gap via the noon hack (anchoring LocalDate → LocalDateTime
    // at 12:00). The new direct path constructs the LocalDateTime purely
    // from decomposed fields — no timezone involvement — so the gap cannot
    // affect it.

    @Test
    fun dstGapDateDirectPath() {
        val date = LocalDate(2000, 3, 19)
        val dt = TypeConversion.castScalar(LocalDateTime::class, date)
        assertEquals(LocalDateTime(2000, 3, 19, 0, 0, 0, 0), dt)
    }

    @Test
    fun dstGapDateRoundTrip() {
        val date = LocalDate(2000, 3, 19)
        val dt = TypeConversion.castScalar(LocalDateTime::class, date)
        val back = TypeConversion.castScalar(LocalDate::class, dt!!)
        assertEquals(date, back)
    }
}
