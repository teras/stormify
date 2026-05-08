package onl.ycode.stormify.schemasync

internal fun argValue(args: Array<String>, name: String): String? {
    val idx = args.indexOfFirst { it == name || it.startsWith("$name=") }
    if (idx < 0) return null
    val arg = args[idx]
    if (arg.contains('=')) return arg.substringAfter('=')
    val next = args.getOrNull(idx + 1) ?: return null
    // Don't swallow another flag as this flag's value.
    if (next.startsWith("--") || next.startsWith("-")) return null
    return next
}

internal fun argValuesAll(args: Array<String>, name: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == name && i + 1 < args.size -> { out += args[i + 1]; i += 2; continue }
            a.startsWith("$name=") -> { out += a.substringAfter('='); i += 1; continue }
            else -> i += 1
        }
    }
    return out
}
