package demo

import onl.ycode.stormify.DbValue

/**
 * Task priority with custom database values via [DbValue].
 * Without DbValue, the ordinal (0, 1, 2) would be stored instead.
 */
enum class Priority(override val dbValue: Int) : DbValue {
    LOW(10),
    MEDIUM(20),
    HIGH(30)
}
