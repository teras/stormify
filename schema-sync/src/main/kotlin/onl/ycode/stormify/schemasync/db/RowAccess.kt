package onl.ycode.stormify.schemasync.db

/**
 * Map-row accessors for the rows returned by `Stormify.read<Map<String, Any?>>`.
 * Stormify lowercases the column labels, so callers can rely on lowercase keys
 * regardless of how the SQL spelled the column. The accessors smooth over
 * driver-specific value types (e.g. one driver returns `Long` for column
 * lengths, another returns `Int`).
 */

internal typealias Row = Map<String, Any?>

internal fun Row.str(key: String): String? = this[key] as? String

internal fun Row.intOrZero(key: String): Int = (this[key] as? Number)?.toInt() ?: 0

internal fun Row.intOrNull(key: String): Int? = (this[key] as? Number)?.toInt()

internal fun Row.boolFromYesNo(key: String, default: Boolean = false): Boolean =
    when (val v = this[key]) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.equals("yes", ignoreCase = true) || v == "Y"
        else -> default
    }
