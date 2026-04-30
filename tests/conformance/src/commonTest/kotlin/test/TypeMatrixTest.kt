// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlin.time.ExperimentalTime::class)

package test

import com.ionspin.kotlin.bignum.decimal.BigDecimal as IonBigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger as IonBigInteger
import kotlinx.datetime.LocalDate as KxLocalDate
import kotlinx.datetime.LocalDateTime as KxLocalDateTime
import kotlinx.datetime.LocalTime as KxLocalTime
import kotlin.test.Test
import kotlin.time.Instant as KtInstant

/**
 * Common-platform Kotlin types: τρέχει σε JVM, Native, Android, iOS.
 * Java-specific τύποι (`java.util.Date`, `java.sql.*`, `java.time.*`,
 * `java.math.BigDecimal/BigInteger`, `java.util.UUID`) ζουν στο
 * `jvmBasedTest/TypeMatrixTestJava.kt`.
 */
open class TypeMatrixTest {
    // --- Primitives & basic Kotlin types ---
    @Test fun byteValue() = runTypeMatrix("Byte", 42.toByte())
    @Test fun shortValue() = runTypeMatrix("Short", 1234.toShort())
    @Test fun intValue() = runTypeMatrix("Int", 12345)
    @Test fun longValue() = runTypeMatrix("Long", 1234567890L)
    @Test fun floatValue() = runTypeMatrix("Float", 3.14f)
    @Test fun doubleValue() = runTypeMatrix("Double", 2.71828)
    @Test fun booleanTrue() = runTypeMatrix("BooleanTrue", true)
    @Test fun booleanFalse() = runTypeMatrix("BooleanFalse", false)
    @Test fun stringValue() = runTypeMatrix("String", "hello")
    @Test fun charValue() = runTypeMatrix("Char", 'A')
    @Test fun byteArrayValue() = runTypeMatrix("ByteArray", byteArrayOf(1, 2, 3, 4, 5))
    @Test fun charArrayValue() = runTypeMatrix("CharArray", charArrayOf('A', 'B', 'C'))

    // --- Numeric large (ionspin, cross-platform) ---
    @Test fun ionspinBigDecimal() = runTypeMatrix("IonBigDecimal", IonBigDecimal.parseString("123456789.123456"))
    @Test fun ionspinBigInteger() = runTypeMatrix("IonBigInteger", IonBigInteger.parseString("123456789012345"))

    // --- Temporal kotlinx ---
    @Test fun kotlinxLocalDate() = runTypeMatrix("KxLocalDate", KxLocalDate(2024, 6, 15))
    @Test fun kotlinxLocalTime() = runTypeMatrix("KxLocalTime", KxLocalTime(14, 30, 45))
    @Test fun kotlinxLocalDateTime() = runTypeMatrix("KxLocalDateTime", KxLocalDateTime(2024, 6, 15, 14, 30, 45))
    @Test fun kotlinTimeInstant() = runTypeMatrix("KtInstant", KtInstant.fromEpochSeconds(1_700_000_000L))

    // --- Enums (raw bind, χωρίς entity-level enum-to-X conversion) ---
    @Test fun enumPlain() = runTypeMatrix("PlainStatus", PlainStatus.ACTIVE)
    @Test fun enumDbValue() = runTypeMatrix("CustomStatus", CustomStatus.BANNED)

    // --- Null ---
    @Test fun nullValue() = runTypeMatrix("Null", null)
}
