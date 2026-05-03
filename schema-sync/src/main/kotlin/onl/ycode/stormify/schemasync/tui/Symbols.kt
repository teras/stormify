package onl.ycode.stormify.schemasync.tui

object Symbols {
    var ascii: Boolean = false
    val vbar: String get() = if (ascii) "|" else "│"
    val hbar: String get() = if (ascii) "-" else "─"
    val tdown: Char get() = if (ascii) '+' else '┬'
    val tup: Char get() = if (ascii) '+' else '┴'
    val cornerTL: Char get() = if (ascii) '+' else '┌'
    val cornerTR: Char get() = if (ascii) '+' else '┐'
    val cornerBL: Char get() = if (ascii) '+' else '└'
    val cornerBR: Char get() = if (ascii) '+' else '┘'
    val ellipsis: String get() = if (ascii) "..." else "…"
    val arrow: String get() = if (ascii) "->" else "→"
    val slotMark: Char get() = if (ascii) '#' else '█'
    val slotEmpty: Char get() = if (ascii) '.' else '·'
    val selectMark: String get() = if (ascii) ">>" else "▶▶"
    val selectEmpty: String get() = "  "
}
