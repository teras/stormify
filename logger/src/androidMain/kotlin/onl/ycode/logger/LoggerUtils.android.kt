package onl.ycode.logger

import java.text.SimpleDateFormat
import java.util.*

private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

/**
 * Replaces positional `{0}`, `{1}` and SLF4J-style `{}` placeholders with the
 * corresponding arguments.
 *
 * This intentionally does NOT use [java.text.MessageFormat] because it interprets
 * `{` and `}` as format directives in ways that break SQL logging (e.g. treating
 * parentheses as sub-format patterns). Instead we handle the two placeholder
 * styles that Stormify actually emits:
 *
 * - **`{0}`, `{1}`, …** — emitted by the common [format] wrapper in LoggerUtils.kt
 *   which converts `{}` → `{0}` before calling this function.
 * - **`{}`** — bare SLF4J-style placeholders (sequential, no index).
 */
internal actual fun messageFormat(message: String, args: Array<out Any?>): String {
    if (args.isEmpty()) return message
    // First pass: indexed placeholders {0}, {1}, …
    var result = message
    for (i in args.indices) {
        result = result.replace("{$i}", args[i]?.toString() ?: "null")
    }
    // Second pass: any remaining bare {} (sequential)
    var argIdx = 0
    val sb = StringBuilder(result.length + 32)
    var i = 0
    while (i < result.length) {
        if (i + 1 < result.length && result[i] == '{' && result[i + 1] == '}' && argIdx < args.size) {
            sb.append(args[argIdx]?.toString() ?: "null")
            argIdx++
            i += 2
        } else {
            sb.append(result[i])
            i++
        }
    }
    return sb.toString()
}

internal actual fun nowFormat(): String = dateFormat.format(Date())
