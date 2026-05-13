// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

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
    // Numeric values είναι μικρά ώστε να χωράνε ακόμη και σε SMALLINT (max 32767).
    // Το test εστιάζει στο type acceptance, όχι στο value-range coverage.
    @Test fun byteValue() = runTypeMatrix("Byte", 42.toByte(), NUMERIC_LIKE)
    @Test fun shortValue() = runTypeMatrix("Short", 1234.toShort(), NUMERIC_LIKE)
    @Test fun intValue() = runTypeMatrix("Int", 12345, NUMERIC_LIKE)
    @Test fun longValue() = runTypeMatrix("Long", 12345L, NUMERIC_LIKE)
    // Narrow ints truncate fractionals; 4-byte REAL truncates Double precision.
    @Test fun floatValue() = runTypeMatrix("Float", 3.14f, NUMERIC_LIKE,
        excludeColumns = setOf("c_smallint", "c_int", "c_bigint"))
    @Test fun doubleValue() = runTypeMatrix("Double", 2.71828, NUMERIC_LIKE,
        excludeColumns = setOf("c_smallint", "c_int", "c_bigint", "c_real"))
    @Test fun booleanTrue() = runTypeMatrix("BooleanTrue", true, BOOLEAN_LIKE)
    @Test fun booleanFalse() = runTypeMatrix("BooleanFalse", false, BOOLEAN_LIKE)
    @Test fun stringValue() = runTypeMatrix("String", "hello", TEXT_ONLY)
    @Test fun charValue() = runTypeMatrix("Char", 'A', TEXT_ONLY)
    @Test fun byteArrayValue() = runTypeMatrix("ByteArray", byteArrayOf(1, 2, 3, 4, 5), BLOB_ONLY)
    @Test fun charArrayValue() = runTypeMatrix("CharArray", charArrayOf('A', 'B', 'C'), TEXT_ONLY)

    // --- Numeric large (ionspin, cross-platform) ---
    // Integer-valued — drivers that send BigDecimal as text (native postgres)
    // reject decimal points when writing to integer columns. The matrix
    // exercises type acceptance, not value-range coverage.
    @Test fun ionspinBigDecimal() =
        runTypeMatrix("IonBigDecimal", IonBigDecimal.parseString("123"), NUMERIC_LIKE)
    @Test fun ionspinBigInteger() =
        runTypeMatrix("IonBigInteger", IonBigInteger.parseString("12345"), NUMERIC_LIKE)

    // --- Temporal kotlinx ---
    @Test fun kotlinxLocalDate() =
        runTypeMatrix("KxLocalDate", KxLocalDate(2024, 6, 15), DATE_AND_TIMESTAMP)
    @Test fun kotlinxLocalTime() =
        runTypeMatrix("KxLocalTime", KxLocalTime(14, 30, 45), TIME_ONLY)
    @Test fun kotlinxLocalDateTime() =
        runTypeMatrix("KxLocalDateTime", KxLocalDateTime(2024, 6, 15, 14, 30, 45), TIMESTAMP_ONLY)
    @Test fun kotlinTimeInstant() =
        runTypeMatrix("KtInstant", KtInstant.fromEpochSeconds(1_700_000_000L), TIMESTAMP_ONLY)
    @Test fun kotlinUuid() = runTypeMatrix(
        "KotlinUuid",
        kotlin.uuid.Uuid.parse("12345678-1234-1234-1234-123456789012"),
        TEXT_ONLY,
    )

    // --- Enums (raw bind, χωρίς entity-level enum-to-X conversion) ---
    @Test fun enumPlain() = runTypeMatrix("PlainStatus", PlainStatus.ACTIVE, TEXT_ONLY)
    @Test fun enumDbValue() = runTypeMatrix("CustomStatus", CustomStatus.BANNED, TEXT_ONLY)

    // --- Null ---
    @Test fun nullValue() = runTypeMatrix<Int>("Null", null, ALL_CATS)
}
