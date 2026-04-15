// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

/**
 * Shape-specific JSON reader for the narrow structures exposed by this
 * package. Not a general-purpose JSON library — handles exactly the value
 * kinds the types here use (strings, ints, booleans, nested objects), plus
 * a generic [skipValue] for forward-compatibility on unknown top-level
 * keys. Supports the full JSON string-escape set defined by the spec.
 * Zero external dependencies.
 */
internal class JsonReader(private val s: String) {
    private var pos = 0

    private fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

    private fun require(cond: Boolean, lazyMsg: () -> String) {
        if (!cond) throw IllegalArgumentException(lazyMsg())
    }

    private fun ensureAvailable(ctx: String) {
        require(pos < s.length) { "Unexpected end of input while reading $ctx (at offset $pos)" }
    }

    fun peek(): Char {
        skipWs()
        ensureAvailable("token")
        return s[pos]
    }

    fun expect(ch: Char) {
        skipWs()
        ensureAvailable("'$ch'")
        require(s[pos] == ch) { "Expected '$ch' at offset $pos, got '${s[pos]}'" }
        pos++
    }

    /** Throws unless only whitespace remains after the current position. */
    fun expectEndOfInput() {
        skipWs()
        require(pos >= s.length) { "Unexpected trailing content at offset $pos" }
    }

    fun readString(): String {
        skipWs()
        ensureAvailable("string")
        require(s[pos] == '"') { "Expected string at offset $pos, got '${s[pos]}'" }
        pos++
        val start = pos
        // Fast scan: no escape → substring directly, avoiding StringBuilder.
        while (pos < s.length && s[pos] != '"' && s[pos] != '\\') pos++
        ensureAvailable("closing quote")
        if (s[pos] == '"') { val r = s.substring(start, pos); pos++; return r }
        // Slow path: at least one escape.
        val sb = StringBuilder()
        sb.append(s, start, pos)
        while (pos < s.length && s[pos] != '"') {
            val c = s[pos++]
            if (c != '\\') { sb.append(c); continue }
            ensureAvailable("escape")
            when (val e = s[pos++]) {
                '"', '\\', '/' -> sb.append(e)
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    require(pos + 4 <= s.length) { "Truncated \\u escape at offset $pos" }
                    sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                    pos += 4
                }
                else -> throw IllegalArgumentException("Invalid escape \\$e at offset ${pos - 1}")
            }
        }
        ensureAvailable("closing quote")
        pos++
        return sb.toString()
    }

    fun readInt(): Int {
        skipWs()
        ensureAvailable("integer")
        val start = pos
        val neg = s[pos] == '-'; if (neg) pos++
        val digitsFrom = pos
        var n = 0
        while (pos < s.length && s[pos] in '0'..'9') {
            val d = s[pos].code - 48
            // Overflow-safe build — detect before appending.
            require(n <= (Int.MAX_VALUE - d) / 10) { "Integer overflow at offset $start" }
            n = n * 10 + d
            pos++
        }
        require(pos > digitsFrom) { "Expected integer at offset $start" }
        return if (neg) -n else n
    }

    fun readBool(): Boolean {
        skipWs()
        return when {
            s.regionMatches(pos, "true", 0, 4) -> { pos += 4; true }
            s.regionMatches(pos, "false", 0, 5) -> { pos += 5; false }
            else -> throw IllegalArgumentException("Expected boolean at offset $pos")
        }
    }

    /**
     * Advances past an arbitrary JSON value without interpreting it. Used for
     * forward-compatible tolerance of unknown top-level keys.
     */
    fun skipValue() {
        when (peek()) {
            '"' -> readString()
            '{' -> {
                expect('{')
                if (peek() != '}') while (true) {
                    readString(); expect(':'); skipValue()
                    if (peek() == ',') expect(',') else break
                }
                expect('}')
            }
            '[' -> {
                expect('[')
                if (peek() != ']') while (true) {
                    skipValue()
                    if (peek() == ',') expect(',') else break
                }
                expect(']')
            }
            't', 'f' -> readBool()
            'n' -> {
                require(s.regionMatches(pos, "null", 0, 4)) { "Expected null at offset $pos" }
                pos += 4
            }
            else -> readInt()
        }
    }

    /**
     * Reads a JSON object whose values are produced by [readValue]. When
     * [readValue] returns `null`, the entry is silently dropped — useful for
     * caller-defined validation (e.g. unknown enum values).
     */
    inline fun <V : Any> readMap(readValue: () -> V?): Map<String, V> {
        expect('{'); val m = HashMap<String, V>()
        if (peek() != '}') while (true) {
            val k = readString(); expect(':')
            val v = readValue()
            if (v != null) m[k] = v
            if (peek() == ',') expect(',') else break
        }
        expect('}'); return m
    }
}

/**
 * Escapes [s] into a JSON string literal, surrounded by double quotes, with
 * control characters and quote/backslash properly escaped. Fast path: when
 * no character requires escaping (typical for alias values), returns the
 * original string wrapped in quotes without allocating a StringBuilder.
 */
internal fun jsonQuote(s: String): String {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '"' || c == '\\' || c.code < 0x20) break
        i++
    }
    if (i == s.length) return "\"$s\""
    val sb = StringBuilder(s.length + 4)
    sb.append('"'); sb.append(s, 0, i)
    while (i < s.length) {
        val c = s[i]
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c == '\u000C' -> sb.append("\\f")
            c.code < 0x20 -> {
                sb.append("\\u")
                val hex = c.code.toString(16)
                var k = hex.length; while (k < 4) { sb.append('0'); k++ }
                sb.append(hex)
            }
            else -> sb.append(c)
        }
        i++
    }
    sb.append('"')
    return sb.toString()
}
