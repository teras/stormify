// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import java.math.BigDecimal as JmBigDecimal
import java.math.BigInteger as JmBigInteger
import java.sql.Date as SqlDate
import java.sql.Time as SqlTime
import java.sql.Timestamp as SqlTimestamp
import java.time.Instant as JtInstant
import java.time.LocalDate as JtLocalDate
import java.time.LocalDateTime as JtLocalDateTime
import java.time.LocalTime as JtLocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date as UtilDate
import java.util.UUID
import kotlin.test.Test

/**
 * Java-platform additions στο matrix test. Τρέχει σε JVM (real JDBC) και
 * Android (Robolectric) μέσω του `jvmBasedTest` source set. Στο Native/iOS
 * αυτοί οι τύποι δεν υπάρχουν.
 */
open class TypeMatrixTestJava {
    private val EPOCH_MS = 1_700_000_000_000L

    // --- Temporal Java ---
    @Test fun javaUtilDate() = runTypeMatrix("javaUtilDate", UtilDate(EPOCH_MS))
    @Test fun javaSqlDate() = runTypeMatrix("javaSqlDate", SqlDate(EPOCH_MS))
    @Test fun javaSqlTime() = runTypeMatrix("javaSqlTime", SqlTime(EPOCH_MS))
    @Test fun javaSqlTimestamp() = runTypeMatrix("javaSqlTimestamp", SqlTimestamp(EPOCH_MS))
    @Test fun javaTimeLocalDate() = runTypeMatrix("javaTimeLocalDate", JtLocalDate.of(2024, 6, 15))
    @Test fun javaTimeLocalTime() = runTypeMatrix("javaTimeLocalTime", JtLocalTime.of(14, 30, 45))
    @Test fun javaTimeLocalDateTime() = runTypeMatrix(
        "javaTimeLocalDateTime",
        JtLocalDateTime.of(2024, 6, 15, 14, 30, 45),
    )
    @Test fun javaTimeInstant() = runTypeMatrix("javaTimeInstant", JtInstant.ofEpochSecond(1_700_000_000L))
    @Test fun javaTimeOffsetDateTime() = runTypeMatrix(
        "javaTimeOffsetDateTime",
        OffsetDateTime.of(JtLocalDateTime.of(2024, 6, 15, 14, 30, 45), ZoneOffset.UTC),
    )
    @Test fun javaTimeZonedDateTime() = runTypeMatrix(
        "javaTimeZonedDateTime",
        ZonedDateTime.of(JtLocalDateTime.of(2024, 6, 15, 14, 30, 45), ZoneOffset.UTC),
    )

    // --- Numeric large (java.math) ---
    @Test fun javaMathBigDecimal() = runTypeMatrix("javaMathBigDecimal", JmBigDecimal("123456789.123456"))
    @Test fun javaMathBigInteger() = runTypeMatrix("javaMathBigInteger", JmBigInteger("123456789012345"))

    // --- Other ---
    @Test fun javaUtilUuid() = runTypeMatrix(
        "javaUtilUUID",
        UUID.fromString("12345678-1234-1234-1234-123456789012"),
    )
}
