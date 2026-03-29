// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

fun interface NamingPolicy {
    fun convert(name: String): String

    companion object {
        val CAMEL_CASE: NamingPolicy = NamingPolicy { it }
        val LOWER_CASE_WITH_UNDERSCORES: NamingPolicy = NamingPolicy { camelToSnake(it, false) }
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
