package onl.ycode.stormify.schemasync.tui

object Symbols {
    var ascii: Boolean = false
    val vbar: String get() = if (ascii) "|" else "│"
    val hbar: String get() = if (ascii) "-" else "─"
    val cross: String get() = if (ascii) "-+-" else "─┼─"
    val ellipsis: String get() = if (ascii) "..." else "…"
    val arrow: String get() = if (ascii) "->" else "→"
    val dash: String get() = if (ascii) "--" else "—"
}
