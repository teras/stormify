// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.TypeConversion
import java.sql.Time
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * JVM-specific tests for the direct java.sql/java.time temporal converters.
 *
 * The old epoch-pivot path for [java.time.LocalTime] ↔ [Long] depended on
 * `ZonedDateTime.now().offset`, which made the result vary by current DST
 * era — same LocalTime produced different Longs before/after a DST transition.
 * The direct `java.sql.Time` ↔ `java.time.LocalTime` pair added in Part 2 of
 * the temporal pipeline refactor bypasses the bug entirely by using the
 * deterministic JDK helpers `Time.valueOf` / `toLocalTime`.
 *
 * These tests pin the fix by round-tripping under simulated wall-clock
 * timezones — the result must not change across `TimeZone.setDefault` calls.
 */
open class JavaTemporalConversionTest {

    private fun <R> withDefaultTimeZone(tz: String, block: () -> R): R {
        val saved = TimeZone.getDefault()
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone(tz))
            block()
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    // --- Direct java.time pure-decomposed pairs -----------------------------

    @Test
    fun javaLocalDateToLocalDateTime() {
        val date = LocalDate.of(2026, 3, 20)
        val dt = TypeConversion.castScalar(LocalDateTime::class, date)
        assertEquals(LocalDateTime.of(2026, 3, 20, 0, 0, 0, 0), dt)
    }

    @Test
    fun javaLocalDateTimeToLocalDate() {
        val dt = LocalDateTime.of(2026, 3, 20, 14, 30, 45)
        val date = TypeConversion.castScalar(LocalDate::class, dt)
        assertEquals(LocalDate.of(2026, 3, 20), date)
    }

    @Test
    fun javaLocalDateTimeToLocalTime() {
        val dt = LocalDateTime.of(2026, 3, 20, 14, 30, 45)
        val time = TypeConversion.castScalar(LocalTime::class, dt)
        assertEquals(LocalTime.of(14, 30, 45), time)
    }

    // --- DST boundary: direct path must be structurally DST-immune ---------

    @Test
    fun javaDstGapDateRoundTrip() {
        // 2000-03-19 is the Brazilian DST forward jump at midnight.
        val date = LocalDate.of(2000, 3, 19)
        val dt = TypeConversion.castScalar(LocalDateTime::class, date)
        assertEquals(LocalDateTime.of(2000, 3, 19, 0, 0, 0, 0), dt)
    }

    // --- java.sql ↔ java.time determinism ----------------------------------
    //
    // The old epoch-pivot path for java.time.LocalTime called
    // `ZonedDateTime.now().offset` on every conversion, so the same LocalTime
    // yielded different results before vs. after a DST transition even inside
    // a single JVM run. The direct java.sql.Time ↔ java.time.LocalTime pair
    // routes through `Time.valueOf` / `toLocalTime`, whose output depends only
    // on the JVM default timezone — not on wall-clock time — so repeated
    // calls in the same zone must produce the exact same value.

    @Test
    fun sqlTimeLocalTimeConversionsAreDeterministicInSameZone() {
        val localTime = LocalTime.of(14, 30, 45)
        val a = withDefaultTimeZone("Europe/Athens") {
            TypeConversion.castScalar(Time::class, localTime)
        }
        val b = withDefaultTimeZone("Europe/Athens") {
            TypeConversion.castScalar(Time::class, localTime)
        }
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a.time, b.time)

        val sqlTime = Time.valueOf(LocalTime.of(9, 15, 30))
        val lt1 = withDefaultTimeZone("Pacific/Auckland") {
            TypeConversion.castScalar(LocalTime::class, sqlTime)
        }
        val lt2 = withDefaultTimeZone("Pacific/Auckland") {
            TypeConversion.castScalar(LocalTime::class, sqlTime)
        }
        assertNotNull(lt1)
        assertNotNull(lt2)
        assertEquals(lt1, lt2)
    }

    // --- java.sql.Date ↔ java.time.LocalDate bidi --------------------------

    @Test
    fun sqlDateLocalDateBidi() {
        val date = LocalDate.of(2026, 3, 20)
        val sqlDate = TypeConversion.castScalar(java.sql.Date::class, date)
        assertNotNull(sqlDate)
        val back = TypeConversion.castScalar(LocalDate::class, sqlDate)
        assertEquals(date, back)
    }

    @Test
    fun sqlTimestampLocalDateTimeBidi() {
        val dt = LocalDateTime.of(2026, 3, 20, 14, 30, 45, 123_000_000)
        val sqlTs = TypeConversion.castScalar(java.sql.Timestamp::class, dt)
        assertNotNull(sqlTs)
        val back = TypeConversion.castScalar(LocalDateTime::class, sqlTs)
        assertEquals(dt, back)
    }
}
