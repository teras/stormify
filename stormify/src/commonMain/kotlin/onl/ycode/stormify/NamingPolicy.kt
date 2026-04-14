// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * Built-in naming policies for converting Kotlin property/class names to database column/table names.
 *
 * A naming policy is simply a `(String) -> String` function. Use one of the presets below
 * (e.g. `NamingPolicy.LOWER_CASE_WITH_UNDERSCORES`) or assign your own lambda directly:
 *
 * ```kotlin
 * stormify.namingPolicy = { "tbl_${it.lowercase()}" }
 * ```
 */
object NamingPolicy {
    /** Keeps names as-is (camelCase). */
    val CAMEL_CASE: (String) -> String = { it }

    /** Converts camelCase to lower_case_with_underscores (snake_case). This is the default. */
    val LOWER_CASE_WITH_UNDERSCORES: (String) -> String = { camelToSnake(it, false) }

    /** Converts camelCase to UPPER_CASE_WITH_UNDERSCORES (SCREAMING_SNAKE_CASE). */
    val UPPER_CASE_WITH_UNDERSCORES: (String) -> String = { camelToSnake(it, true) }
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
