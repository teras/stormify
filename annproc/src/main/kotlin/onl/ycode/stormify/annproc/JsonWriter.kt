// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.annproc

/**
 * Hand-rolled JSON writer for entity metadata. Avoids pulling
 * `kotlinx.serialization` (and its compiler plugin) into `annproc`'s
 * runtime — keeps the annotation processor light. The schema is fixed
 * and small, so manual emission is straightforward and predictable.
 */
internal class JsonWriter {
    private val sb = StringBuilder()

    fun obj(block: JsonObject.() -> Unit): String {
        JsonObject(sb).apply { open(); block(); close() }
        return sb.toString()
    }
}

internal class JsonObject(private val sb: StringBuilder) {
    private var first = true

    fun open() { sb.append('{') }
    fun close() { sb.append('}') }

    fun str(key: String, value: String) {
        sep()
        sb.append('"').append(key).append("\":")
        appendString(value)
    }

    fun bool(key: String, value: Boolean) {
        sep()
        sb.append('"').append(key).append("\":").append(value)
    }

    fun int(key: String, value: Int) {
        sep()
        sb.append('"').append(key).append("\":").append(value)
    }

    fun strList(key: String, values: List<String>) {
        sep()
        sb.append('"').append(key).append("\":[")
        values.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            appendString(v)
        }
        sb.append(']')
    }

    fun objList(key: String, values: List<(JsonObject) -> Unit>) {
        sep()
        sb.append('"').append(key).append("\":[")
        values.forEachIndexed { i, build ->
            if (i > 0) sb.append(',')
            JsonObject(sb).apply { open(); build(this); close() }
        }
        sb.append(']')
    }

    private fun sep() {
        if (first) first = false else sb.append(',')
    }

    private fun appendString(value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
