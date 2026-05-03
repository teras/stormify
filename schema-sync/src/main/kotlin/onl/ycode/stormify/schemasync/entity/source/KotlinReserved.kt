package onl.ycode.stormify.schemasync.entity.source

/**
 * Kotlin hard keywords plus a handful of soft/modifier keywords that are
 * unsafe to use as plain property names. When a generated property name lands
 * on one of these, the writer must wrap it in backticks (`` `class` ``).
 *
 * Source: https://kotlinlang.org/docs/keyword-reference.html
 */
private val KOTLIN_RESERVED = setOf(
    // Hard keywords — always reserved.
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while",
    // Soft/modifier keywords that are commonly tripped over as identifiers.
    "by", "constructor", "delegate", "dynamic", "field", "file", "init",
    "param", "property", "receiver", "set", "setparam", "value", "where",
)

/**
 * Wraps [name] in backticks when it collides with a Kotlin keyword or starts
 * with a digit; returns it unchanged otherwise. Empty input is returned as-is.
 */
internal fun safeKotlinIdentifier(name: String): String {
    if (name.isEmpty()) return name
    val needsQuoting = name in KOTLIN_RESERVED || name[0].isDigit() ||
        name.any { !it.isLetterOrDigit() && it != '_' }
    return if (needsQuoting) "`$name`" else name
}
