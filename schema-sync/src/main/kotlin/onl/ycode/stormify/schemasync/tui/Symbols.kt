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
    /** Em dash used as a separator inside titles (`Diff — name`). */
    val dash: String get() = if (ascii) "-" else "—"
    /** Section rule prefix/suffix in diff previews (`─── DB needs ───`). */
    val rule: String get() = if (ascii) "---" else "───"
    /** Inline "not equal" marker before a type-conflict line. */
    val neq: String get() = if (ascii) "!=" else "≠"
}
