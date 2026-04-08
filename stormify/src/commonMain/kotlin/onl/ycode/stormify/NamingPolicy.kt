// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * Strategy for converting Kotlin property/class names to database column/table names.
 * Implement this interface to provide a custom naming convention.
 */
fun interface NamingPolicy {
    /** Converts a Kotlin name (e.g. `firstName`) to a database name (e.g. `first_name`). */
    fun convert(name: String): String

    companion object {
        /** Keeps names as-is (camelCase). */
        val CAMEL_CASE: NamingPolicy = NamingPolicy { it }

        /** Converts camelCase to lower_case_with_underscores (snake_case). This is the default. */
        val LOWER_CASE_WITH_UNDERSCORES: NamingPolicy = NamingPolicy { camelToSnake(it, false) }

        /** Converts camelCase to UPPER_CASE_WITH_UNDERSCORES (SCREAMING_SNAKE_CASE). */
        val UPPER_CASE_WITH_UNDERSCORES: NamingPolicy = NamingPolicy { camelToSnake(it, true) }
    }
}

internal fun camelToSnake(str: String, upper: Boolean): String {
    val result = StringBuilder()
    for (c in str) {
        if (c.isUpperCase() && result.isNotEmpty()) {
            result.append('_')
            result.append(if (upper) c.uppercaseChar() else c.lowercaseChar())
        } else
            result.append(if (upper) c.uppercaseChar() else c.lowercaseChar())
    }
    return result.toString()
}
