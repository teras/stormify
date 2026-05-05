package onl.ycode.stormify.schemasync.db

/**
 * Translates a raw DB column DEFAULT (as returned by JDBC `COLUMN_DEF` /
 * Oracle `DATA_DEFAULT`) to a Kotlin literal that, when emitted in entity
 * source, produces an equivalent value. Returns null when the default is
 * a function call, sequence, expression, or anything else that can't be
 * safely represented as a static Kotlin literal — the caller is expected
 * to fall back to a synthetic initializer and let the DB-side DEFAULT do
 * the work at INSERT time.
 *
 * `kotlinType` is the simple type name the property will carry (`Int`,
 * `String`, `Boolean`, `BigDecimal`, …). It steers numeric suffix choice
 * and the boolean/numeric ambiguity (`0` → `false` vs `0`).
 */
internal fun dbDefaultToKotlinLiteral(rawDefault: String?, kotlinType: String): String? {
    val raw = rawDefault?.trim() ?: return null
    if (raw.isEmpty()) return null
    val unwrapped = stripWrappers(raw)
    if (unwrapped.equals("null", ignoreCase = true)) return null
    val baseType = kotlinType.removeSuffix("?").substringAfterLast('.').trim()

    // Quoted string literal — Postgres / MariaDB / MSSQL render text defaults
    // as `'foo'` (single quotes, embedded `''` for literal apostrophe). Oracle
    // adds a trailing space sometimes; strip it.
    if (unwrapped.length >= 2 && unwrapped.startsWith('\'') && unwrapped.endsWith('\'')) {
        val inner = unwrapped.substring(1, unwrapped.length - 1).replace("''", "'")
        return when (baseType) {
            "Boolean" -> when (inner.lowercase()) { "true", "1", "t", "y" -> "true"; "false", "0", "f", "n" -> "false"; else -> null }
            else -> "\"" + escapeKotlinString(inner) + "\""
        }
    }

    // Boolean keywords / numeric flags.
    if (baseType == "Boolean") {
        return when (unwrapped.lowercase()) {
            "true", "1" -> "true"
            "false", "0" -> "false"
            else -> null
        }
    }

    // Numeric literal — accept optional sign and one suffix; strip Postgres
    // underscore separators that drivers sometimes leave in.
    val numericRe = Regex("""^[-+]?\d[\d_]*(\.\d[\d_]*)?$""")
    if (numericRe.matches(unwrapped)) {
        val cleaned = unwrapped.replace("_", "")
        return when (baseType) {
            "Long" -> if (cleaned.contains('.')) null else "${cleaned}L"
            "Float" -> "${cleaned}f"
            "Double" -> if (cleaned.contains('.')) cleaned else "$cleaned.0"
            "Int", "Integer", "Short", "Byte" -> if (cleaned.contains('.')) null else cleaned
            "BigDecimal" -> "java.math.BigDecimal(\"$cleaned\")"
            "BigInteger" -> if (cleaned.contains('.')) null else "java.math.BigInteger(\"$cleaned\")"
            else -> cleaned
        }
    }

    // Anything else (function calls like `nextval('seq')`, `CURRENT_TIMESTAMP`,
    // `SYSDATE`, `gen_random_uuid()`, sub-expressions) is left to the DB.
    return null
}

/** Normalizes a raw DB default for equality comparison against an entity-side
 *  Kotlin literal. Returns the inner unquoted value for strings and a stripped
 *  numeric/keyword form otherwise. Used by the diff. */
internal fun normalizeDbDefault(rawDefault: String?): String? {
    val raw = rawDefault?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val unwrapped = stripWrappers(raw)
    if (unwrapped.equals("null", ignoreCase = true)) return null
    if (unwrapped.length >= 2 && unwrapped.startsWith('\'') && unwrapped.endsWith('\'')) {
        return unwrapped.substring(1, unwrapped.length - 1).replace("''", "'")
    }
    return unwrapped
}

/** Normalizes a Kotlin entity-side literal (already a valid Kotlin source
 *  fragment) to the same canonical form as [normalizeDbDefault]. */
internal fun normalizeKotlinLiteral(literal: String?): String? {
    val t = literal?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (t == "null") return null
    if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) {
        return unescapeKotlinString(t.substring(1, t.length - 1))
    }
    // Numeric: drop Kotlin suffix and underscores so `1_000L` matches `1000`.
    val numericWithSuffix = Regex("""^([-+]?\d[\d_]*(\.\d[\d_]*)?)[LlFfDd]?$""")
    numericWithSuffix.matchEntire(t)?.let { return it.groupValues[1].replace("_", "") }
    return t
}

/** Strips outer parens and a trailing `::type` cast (Postgres-style). Repeats
 *  while progress is made so `('foo')::text` collapses cleanly. */
private fun stripWrappers(s: String): String {
    var cur = s
    while (true) {
        val next = stripCast(stripOuterParens(cur))
        if (next == cur) return cur
        cur = next
    }
}

private fun stripOuterParens(s: String): String {
    if (!s.startsWith('(') || !s.endsWith(')')) return s
    var depth = 0
    for ((i, c) in s.withIndex()) {
        if (c == '(') depth++
        else if (c == ')') {
            depth--
            if (depth == 0 && i != s.length - 1) return s
        }
    }
    return s.substring(1, s.length - 1).trim()
}

private fun stripCast(s: String): String {
    val idx = s.lastIndexOf("::")
    if (idx <= 0) return s
    // Refuse if the `::` lives inside a quoted region.
    val before = s.substring(0, idx)
    if (before.count { it == '\'' } % 2 != 0) return s
    return before.trim()
}

private fun escapeKotlinString(s: String): String = buildString {
    for (c in s) when (c) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(c)
    }
}

private fun unescapeKotlinString(s: String): String {
    if ('\\' !in s) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
        when (val n = s[i + 1]) {
            '\\' -> sb.append('\\')
            '"' -> sb.append('"')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            else -> { sb.append(c); sb.append(n) }
        }
        i += 2
    }
    return sb.toString()
}
