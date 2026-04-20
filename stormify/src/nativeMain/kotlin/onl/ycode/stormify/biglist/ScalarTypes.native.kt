// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlin.time.ExperimentalTime::class)

package onl.ycode.stormify.biglist

internal actual fun ScalarTypes.registerPlatformScalars() {
    register(Facet.Type.NUMERIC,
        com.ionspin.kotlin.bignum.decimal.BigDecimal::class,
        com.ionspin.kotlin.bignum.integer.BigInteger::class)
    register(Facet.Type.DATE, kotlinx.datetime.LocalDate::class)
    register(Facet.Type.TIME, kotlinx.datetime.LocalTime::class)
    register(Facet.Type.TIMESTAMP, kotlinx.datetime.LocalDateTime::class, kotlin.time.Instant::class)
}
