// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import onl.ycode.kdbc.SQLException
import onl.ycode.kdbc.TypeConversion
import kotlin.reflect.KClass

/**
 * Utility class for type conversion with entity-aware fallback.
 *
 * Scalar conversions are delegated to [TypeConversion] in the KDBC layer.
 * Entity conversions (foreign key → entity object) are handled here.
 */
object TypeUtils {
    /**
     * Convert a value to the target class.
     * For scalar types, delegates to [TypeConversion.castScalar].
     * For non-scalar types, resolves the entity via Stormify.
     */
    @Suppress("UNCHECKED_CAST")
    fun <F : Any, T : Any> castTo(targetClass: KClass<T>, value: F?, stormify: Stormify? = null): T? {
        if (value == null || targetClass.isInstance(value)) return value as T?
        // Enum conversion: Int/Number → enum by ordinal/DbValue, String → enum by name
        if (isEnumClass(targetClass)) {
            return when (value) {
                is Number -> enumFromInt(targetClass, value.toInt())
                is String -> {
                    val intVal = value.toIntOrNull()
                    if (intVal != null) enumFromInt(targetClass, intVal)
                    else enumFromName(targetClass, value)
                }
                else -> throw SQLException("Cannot convert ${value::class.fullName} to enum ${targetClass.fullName}")
            }
        }
        if (!isScalarObject(value)) {
            if (stormify == null)
                throw SQLException("Unable to convert non-scalar object to " + targetClass.fullName + "; missing database context")
            val info = stormify.resolveTableInfo(value::class)
            val item = info.create()
            if (item is StormifyEntity) item._stormify = stormify
            info.setField(item, info.singleKeyDbName, value, stormify)
            return item as T
        }
        return TypeConversion.castScalar(targetClass, value)
    }
}

/**
 * Reports the index of every `?` placeholder in [sql] that lives in
 * executable SQL — i.e. outside the following constructs:
 * - single-quoted string literals `'…'` (with `''` as an embedded quote);
 * - double-quoted identifiers `"…"` (with `""` as an embedded quote);
 * - backtick identifiers `` `…` `` (with `` `` `` as an embedded backtick);
 * - line comments `-- …` up to the next newline;
 * - block comments `/* … */` (non-nesting).
 *
 * Oracle's `q'[…]'` literals and PostgreSQL's nested block comments are
 * intentionally not handled — they are vanishingly rare in parameterised
 * queries and would cost more in complexity than they buy in correctness.
 * An unterminated literal or comment is treated as extending to end-of-input.
 */
internal fun scanPlaceholders(sql: String, onPlaceholder: (Int) -> Unit) {
    var i = 0
    val n = sql.length
    while (i < n) {
        val c = sql[i]
        when {
            c == '\'' || c == '"' || c == '`' -> {
                val quote = c
                i++
                while (i < n) {
                    val d = sql[i++]
                    if (d == quote) {
                        if (i < n && sql[i] == quote) i++  // doubled = embedded
                        else break
                    }
                }
            }
            c == '-' && i + 1 < n && sql[i + 1] == '-' -> {
                while (i < n && sql[i] != '\n') i++
            }
            c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                i += 2
                while (i < n) {
                    if (sql[i] == '*' && i + 1 < n && sql[i + 1] == '/') { i += 2; break }
                    i++
                }
            }
            c == '?' -> { onPlaceholder(i); i++ }
            else -> i++
        }
    }
}

/**
 * Text form of a key value for identity maps (batch hydration, the per-result
 * reference cache). Equal decimals give equal keys whatever their scale or
 * notation (`100`, `100.00`, `1E+2`); every other value keys by `toString()`.
 */
internal fun identityKey(value: Any?): String =
    if (value != null && isBigDecimal(value)) canonicalDecimal(value.toString()) else value.toString()

private val DECIMAL_FORM = Regex("([+-]?)(\\d*)(?:\\.(\\d*))?(?:[eE]([+-]?\\d+))?")

/** Significant digits without leading or trailing zeros, plus the exponent: `100.00` -> `1e2`. */
internal fun canonicalDecimal(text: String): String {
    val match = DECIMAL_FORM.matchEntire(text) ?: return text
    val (sign, intPart, fracPart, expPart) = match.destructured
    val digits = (intPart + fracPart).trimStart('0')
    if (digits.isEmpty()) return "0"
    val significant = digits.trimEnd('0')
    val exponent = expPart.ifEmpty { "0" }.toInt() - fracPart.length + (digits.length - significant.length)
    return (if (sign == "-") "-" else "") + significant + "e" + exponent
}

internal fun nCopies(base: String, delimiter: String, count: Int) =
    if (count <= 0) "" else List(count) { base }.joinToString(delimiter)

internal fun <T> findItemOnce(data: List<T>, key: T, spaceName: String): Int {
    var found: Int = -1
    for (i in data.indices)
        if (key == data[i])
            if (found < 0) found = i
            else throw SQLException("Multiple instances of '$key' found in $spaceName")
    if (found < 0)
        throw SQLException("Unable to find any instances of '$key' in $spaceName")
    return found
}

internal fun isScalarObject(request: Any) =
    request is Number || request is CharSequence || request is Char || request is Boolean
            || request is ByteArray || request is CharArray || request.isOtherPrimitive

internal fun Throwable.throwQuery(reason: String): Nothing = throw asQuery(reason)

internal fun Throwable.asQuery(reason: String): SQLException =
    if (this is SQLException) this else SQLException(reason, this)

internal val KClass<*>.fullName get() = qualifiedName ?: throw SQLException("Unknown class name of class $this")

internal inline fun <T : AutoCloseable, R> T.useWithException(
    message: String,
    block: (T) -> R
) = try {
    use(block)
} catch (e: Throwable) {
    e.throwQuery(message)
}

internal inline fun <R> tryQuery(
    message: String,
    block: () -> R
) = try {
    block()
} catch (e: Throwable) {
    e.throwQuery(message)
}
