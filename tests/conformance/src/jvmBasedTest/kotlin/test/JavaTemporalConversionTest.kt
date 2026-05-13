// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.TypeConversion
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

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

    // --- java.time.Instant ----------------------------------------------------

    @Test fun instantIsKnownScalar() {
        assertTrue(TypeConversion.isKnownScalar(Instant::class))
    }

    @Test fun instantLongRoundTrip() {
        val ms = 1_700_000_000_000L
        val inst = TypeConversion.castScalar(Instant::class, ms)
        assertEquals(Instant.ofEpochMilli(ms), inst)
        assertEquals(ms, TypeConversion.castScalar(Long::class, inst!!))
    }

    @Test fun instantStringIsoLossless() {
        val inst = Instant.parse("2026-03-20T14:30:45.123456789Z")
        val s = TypeConversion.castScalar(String::class, inst)
        assertEquals("2026-03-20T14:30:45.123456789Z", s)
        val back = TypeConversion.castScalar(Instant::class, s!!)
        assertEquals(inst, back)
    }

    @Test fun instantTimestampDirectPreservesNanos() {
        val inst = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L)
        val ts = TypeConversion.castScalar(Timestamp::class, inst)
        assertNotNull(ts)
        assertEquals(123_456_789, ts.nanos)
        val back = TypeConversion.castScalar(Instant::class, ts)
        assertEquals(inst, back)
    }

    @Test fun instantUtilDateDirectMillis() {
        val inst = Instant.ofEpochMilli(1_700_000_000_123L)
        val date = TypeConversion.castScalar(java.util.Date::class, inst)
        assertNotNull(date)
        assertEquals(1_700_000_000_123L, date.time)
        val back = TypeConversion.castScalar(Instant::class, date)
        assertEquals(inst, back)
    }

    @OptIn(ExperimentalTime::class)
    @Test fun instantKotlinTimeInstantInterop() {
        val jInst = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L)
        val kInst = TypeConversion.castScalar(kotlin.time.Instant::class, jInst)
        assertNotNull(kInst)
        assertEquals(jInst.epochSecond, kInst.epochSeconds)
        assertEquals(jInst.nano, kInst.nanosecondsOfSecond)
        val back = TypeConversion.castScalar(Instant::class, kInst)
        assertEquals(jInst, back)
    }

    // --- java.time.OffsetDateTime --------------------------------------------

    @Test fun offsetDateTimeIsKnownScalar() {
        assertTrue(TypeConversion.isKnownScalar(OffsetDateTime::class))
    }

    @Test fun offsetDateTimeStringIsoLossless() {
        val odt = OffsetDateTime.of(2026, 3, 20, 14, 30, 45, 0, ZoneOffset.ofHours(2))
        val s = TypeConversion.castScalar(String::class, odt)
        assertEquals("2026-03-20T14:30:45+02:00", s)
        val back = TypeConversion.castScalar(OffsetDateTime::class, s!!)
        assertEquals(odt, back)
    }

    @Test fun offsetDateTimeLongLossyResetsToUtc() {
        val odt = OffsetDateTime.of(2026, 3, 20, 14, 30, 45, 0, ZoneOffset.ofHours(2))
        val ms = TypeConversion.castScalar(Long::class, odt)
        assertEquals(odt.toInstant().toEpochMilli(), ms)
        val back = TypeConversion.castScalar(OffsetDateTime::class, ms!!)
        // Same instant, but offset reset to UTC anchor.
        assertEquals(odt.toInstant(), back!!.toInstant())
        assertEquals(ZoneOffset.UTC, back.offset)
    }

    @Test fun offsetDateTimeInstantBidi() {
        val inst = Instant.ofEpochSecond(1_700_000_000L, 500_000_000L)
        val odt = TypeConversion.castScalar(OffsetDateTime::class, inst)
        assertNotNull(odt)
        assertEquals(ZoneOffset.UTC, odt.offset)
        assertEquals(inst, odt.toInstant())
    }

    // --- java.time.ZonedDateTime ---------------------------------------------

    @Test fun zonedDateTimeIsKnownScalar() {
        assertTrue(TypeConversion.isKnownScalar(ZonedDateTime::class))
    }

    @Test fun zonedDateTimeStringIsoLossless() {
        val zdt = ZonedDateTime.of(2026, 3, 20, 14, 30, 45, 0, java.time.ZoneId.of("Europe/Athens"))
        val s = TypeConversion.castScalar(String::class, zdt)
        assertEquals("2026-03-20T14:30:45+02:00[Europe/Athens]", s)
        val back = TypeConversion.castScalar(ZonedDateTime::class, s!!)
        assertEquals(zdt, back)
    }

    @Test fun zonedDateTimeLongLossyResetsToUtc() {
        val zdt = ZonedDateTime.of(2026, 3, 20, 14, 30, 45, 0, java.time.ZoneId.of("Europe/Athens"))
        val ms = TypeConversion.castScalar(Long::class, zdt)
        assertEquals(zdt.toInstant().toEpochMilli(), ms)
        val back = TypeConversion.castScalar(ZonedDateTime::class, ms!!)
        assertEquals(zdt.toInstant(), back!!.toInstant())
        assertEquals(ZoneOffset.UTC, back.offset)
    }

    @Test fun zonedDateTimeOffsetDateTimeBidi() {
        val odt = OffsetDateTime.of(2026, 3, 20, 14, 30, 45, 0, ZoneOffset.ofHours(2))
        val zdt = TypeConversion.castScalar(ZonedDateTime::class, odt)
        assertNotNull(zdt)
        assertEquals(odt.toInstant(), zdt.toInstant())
        val back = TypeConversion.castScalar(OffsetDateTime::class, zdt)
        assertEquals(odt, back)
    }

    // --- Cross-temporal via epoch pivot (UTC anchor) -------------------------

    @Test fun instantLocalDateTimeBidi() {
        val inst = Instant.ofEpochMilli(1_700_000_000_123L)
        val ldt = TypeConversion.castScalar(LocalDateTime::class, inst)
        assertEquals(inst.atOffset(ZoneOffset.UTC).toLocalDateTime(), ldt)
        val back = TypeConversion.castScalar(Instant::class, ldt!!)
        assertEquals(inst.toEpochMilli(), back!!.toEpochMilli())
    }

    @Test fun offsetDateTimeLocalDateTimeBidi() {
        val odt = OffsetDateTime.of(2026, 3, 20, 14, 30, 45, 0, ZoneOffset.UTC)
        val ldt = TypeConversion.castScalar(LocalDateTime::class, odt)
        assertEquals(odt.toLocalDateTime(), ldt)
        val back = TypeConversion.castScalar(OffsetDateTime::class, ldt!!)
        assertEquals(odt.toInstant(), back!!.toInstant())
    }

    @Test fun zonedDateTimeLocalDateTimeBidi() {
        val zdt = ZonedDateTime.of(2026, 3, 20, 14, 30, 45, 0, ZoneOffset.UTC)
        val ldt = TypeConversion.castScalar(LocalDateTime::class, zdt)
        assertEquals(zdt.toLocalDateTime(), ldt)
        val back = TypeConversion.castScalar(ZonedDateTime::class, ldt!!)
        assertEquals(zdt.toInstant(), back!!.toInstant())
    }

    @OptIn(ExperimentalTime::class)
    @Test fun instantKotlinxLocalDateTimeBidi() {
        val inst = Instant.ofEpochMilli(1_700_000_000_000L)
        val kxLdt = TypeConversion.castScalar(kotlinx.datetime.LocalDateTime::class, inst)
        assertNotNull(kxLdt)
        val back = TypeConversion.castScalar(Instant::class, kxLdt)
        assertEquals(inst, back)
    }

    @Test fun offsetDateTimeTimestampBidi() {
        val odt = OffsetDateTime.of(2026, 3, 20, 14, 30, 45, 123_456_789, ZoneOffset.ofHours(2))
        val ts = TypeConversion.castScalar(Timestamp::class, odt)
        assertNotNull(ts)
        assertEquals(odt.toInstant().toEpochMilli(), ts.time)
        val back = TypeConversion.castScalar(OffsetDateTime::class, ts)
        assertEquals(odt.toInstant(), back!!.toInstant())
    }

    @Test fun zonedDateTimeTimestampBidi() {
        val zdt = ZonedDateTime.of(2026, 3, 20, 14, 30, 45, 0, java.time.ZoneId.of("Europe/Athens"))
        val ts = TypeConversion.castScalar(Timestamp::class, zdt)
        assertNotNull(ts)
        assertEquals(zdt.toInstant().toEpochMilli(), ts.time)
        val back = TypeConversion.castScalar(ZonedDateTime::class, ts)
        assertEquals(zdt.toInstant(), back!!.toInstant())
    }

    // --- java.time.OffsetTime ------------------------------------------------

    @Test fun offsetTimeIsKnownScalar() {
        assertTrue(TypeConversion.isKnownScalar(java.time.OffsetTime::class))
    }

    @Test fun offsetTimeStringIsoLossless() {
        val ot = java.time.OffsetTime.of(14, 30, 45, 0, ZoneOffset.ofHours(2))
        val s = TypeConversion.castScalar(String::class, ot)
        assertEquals("14:30:45+02:00", s)
        val back = TypeConversion.castScalar(java.time.OffsetTime::class, s!!)
        assertEquals(ot, back)
    }

    @Test fun offsetTimeLocalTimeBidi() {
        val ot = java.time.OffsetTime.of(14, 30, 45, 0, ZoneOffset.ofHours(2))
        val lt = TypeConversion.castScalar(LocalTime::class, ot)
        assertEquals(LocalTime.of(14, 30, 45), lt)
        val back = TypeConversion.castScalar(java.time.OffsetTime::class, lt!!)
        assertEquals(LocalTime.of(14, 30, 45), back!!.toLocalTime())
        assertEquals(ZoneOffset.UTC, back.offset)
    }
}
