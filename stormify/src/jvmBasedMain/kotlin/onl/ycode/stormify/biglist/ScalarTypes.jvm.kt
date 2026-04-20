// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlin.time.ExperimentalTime::class)

package onl.ycode.stormify.biglist

import onl.ycode.stormify.supportsIonspinBigNumbers
import onl.ycode.stormify.supportsKotlinxDatetime

internal actual fun ScalarTypes.registerPlatformScalars() {
    register(Facet.Type.NUMERIC, java.math.BigDecimal::class, java.math.BigInteger::class)
    register(Facet.Type.DATE, java.time.LocalDate::class, java.sql.Date::class)
    register(Facet.Type.TIME, java.time.LocalTime::class, java.sql.Time::class)
    register(Facet.Type.TIMESTAMP,
        java.time.LocalDateTime::class, java.time.Instant::class,
        java.time.OffsetDateTime::class, java.time.ZonedDateTime::class,
        java.sql.Timestamp::class, java.util.Date::class)

    if (supportsKotlinxDatetime) {
        register(Facet.Type.DATE, kotlinx.datetime.LocalDate::class)
        register(Facet.Type.TIME, kotlinx.datetime.LocalTime::class)
        register(Facet.Type.TIMESTAMP, kotlinx.datetime.LocalDateTime::class, kotlin.time.Instant::class)
    }
    if (supportsIonspinBigNumbers) {
        register(Facet.Type.NUMERIC,
            com.ionspin.kotlin.bignum.decimal.BigDecimal::class,
            com.ionspin.kotlin.bignum.integer.BigInteger::class)
    }
}
