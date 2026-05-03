package onl.ycode.stormify.schemasync.model

import kotlinx.serialization.Serializable

/**
 * How Kotlin property names map to DB column names. Mirrors stormify's
 * runtime `NamingPolicy` so the diff and the runtime stay in lock-step.
 */
@Serializable
enum class NamingPolicy {
    /** `userName` ↔ `user_name` (the stormify default). */
    LOWER_CASE_WITH_UNDERSCORES,

    /** `userName` ↔ `USER_NAME`. */
    UPPER_CASE_WITH_UNDERSCORES,

    /** `userName` ↔ `userName` (no transformation). */
    CAMEL_CASE;

    /** Convert a Kotlin identifier (camelCase) to the DB representation. */
    fun fromKotlin(kotlin: String): String = when (this) {
        CAMEL_CASE -> kotlin
        LOWER_CASE_WITH_UNDERSCORES -> camelToSeparated(kotlin, lower = true)
        UPPER_CASE_WITH_UNDERSCORES -> camelToSeparated(kotlin, lower = false)
    }

    /** Convert a DB column name back to a Kotlin identifier. */
    fun toKotlin(db: String): String = when (this) {
        CAMEL_CASE -> db.replaceFirstChar { it.lowercaseChar() }
        LOWER_CASE_WITH_UNDERSCORES, UPPER_CASE_WITH_UNDERSCORES -> separatedToCamel(db)
    }

    private fun camelToSeparated(s: String, lower: Boolean): String = buildString {
        for ((i, c) in s.withIndex()) {
            if (c.isUpperCase() && i > 0) append('_')
            append(if (lower) c.lowercaseChar() else c.uppercaseChar())
        }
    }

    private fun separatedToCamel(s: String): String {
        val parts = s.lowercase().split('_').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return s
        if (parts.size == 1) return parts[0]
        return parts[0] + parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }
}
