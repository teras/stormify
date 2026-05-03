package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.AbstractBorder
import com.googlecode.lanterna.gui2.Border
import com.googlecode.lanterna.gui2.Component
import com.googlecode.lanterna.gui2.ComponentRenderer
import com.googlecode.lanterna.gui2.TextGUIGraphics

/**
 * Titled box border that picks ASCII or Unicode glyphs from [Symbols] and
 * renders column-aware junction characters where the wrapped component's
 * vertical separators meet the top and bottom edges:
 *  - any `│` (vbar) inside [title] is promoted to `┬` so the top edge reads
 *    as one continuous horizontal line through the column separator;
 *  - the bottom edge places `┴` directly below every inner-coordinate column
 *    listed in [crossColumns].
 *
 * @param title         rendered into the top edge with a single-cell space
 *                      on either side (`┌─ Title ─┐`).
 * @param crossColumns  inner-coordinate column indices that mark where the
 *                      wrapped component draws full-height column separators.
 */
class TitledBorder(
    initialTitle: String,
    private val crossColumns: List<Int> = emptyList(),
) : AbstractBorder() {

    /** Mutable so callers can update the title at runtime; setting triggers a redraw. */
    var title: String = initialTitle
        set(value) {
            field = value
            decoratedTitle = if (value.isEmpty()) "" else " $value "
            invalidate()
        }

    private var decoratedTitle: String = if (initialTitle.isEmpty()) "" else " $initialTitle "

    override fun createDefaultRenderer(): ComponentRenderer<Border> = Renderer()

    private inner class Renderer : Border.BorderRenderer {

        override fun getPreferredSize(component: Border): TerminalSize {
            val wrapped: Component? = component.component
            val inner = wrapped?.preferredSize ?: TerminalSize.ZERO
            val titleWidth = if (title.isEmpty()) 0 else title.length + 4
            val cols = maxOf(inner.columns + 2, titleWidth)
            val rows = inner.rows + 2
            return TerminalSize(cols, rows)
        }

        override fun drawComponent(graphics: TextGUIGraphics, component: Border) {
            val w = graphics.size.columns
            val h = graphics.size.rows
            if (w < 2 || h < 2) return

            val themeDef = component.themeDefinition
            graphics.applyThemeStyle(themeDef.normal)

            val hbar = Symbols.hbar[0]
            val vbar = Symbols.vbar[0]

            graphics.setCharacter(0, 0, Symbols.cornerTL)
            graphics.setCharacter(w - 1, 0, Symbols.cornerTR)
            graphics.setCharacter(0, h - 1, Symbols.cornerBL)
            graphics.setCharacter(w - 1, h - 1, Symbols.cornerBR)
            for (x in 1 until w - 1) {
                graphics.setCharacter(x, 0, hbar)
                graphics.setCharacter(x, h - 1, hbar)
            }
            for (y in 1 until h - 1) {
                graphics.setCharacter(0, y, vbar)
                graphics.setCharacter(w - 1, y, vbar)
            }

            // Bottom edge: ┴ where wrapped component's column separators meet
            // the border. crossColumns are inner-coords; border offset is +1.
            for (col in crossColumns) {
                val abs = col + 1
                if (abs in 1 until w - 1) {
                    graphics.setCharacter(abs, h - 1, Symbols.tup)
                }
            }

            if (title.isNotEmpty() && w >= title.length + 4) {
                graphics.applyThemeStyle(themeDef.active)
                graphics.putString(2, 0, decoratedTitle)
                graphics.applyThemeStyle(themeDef.normal)
                // Promote any vbar in the title to ┬ so the top edge reads as
                // a continuous horizontal line with a downward stub.
                val titleStart = 3
                title.forEachIndexed { i, ch ->
                    if (ch == vbar) {
                        graphics.setCharacter(titleStart + i, 0, Symbols.tdown)
                    }
                }
            }

            val wrapped = component.component ?: return
            val innerSize = TerminalSize((w - 2).coerceAtLeast(0), (h - 2).coerceAtLeast(0))
            if (innerSize.columns == 0 || innerSize.rows == 0) return
            val innerGraphics = graphics.newTextGraphics(TerminalPosition(1, 1), innerSize)
            wrapped.draw(innerGraphics)
        }

        override fun getWrappedComponentSize(borderSize: TerminalSize): TerminalSize =
            TerminalSize(
                (borderSize.columns - 2).coerceAtLeast(0),
                (borderSize.rows - 2).coerceAtLeast(0),
            )

        override fun getWrappedComponentTopLeftOffset(): TerminalPosition = TerminalPosition(1, 1)
    }
}

/** Wraps a component with a titled box border (ASCII or Unicode based on [Symbols.ascii]). */
fun Component.withTitledBorder(title: String, crossColumns: List<Int> = emptyList()): TitledBorder =
    TitledBorder(title, crossColumns).apply { component = this@withTitledBorder }

/**
 * Builds a column-header title for [withTitledBorder]. Sections are joined
 * by `│` (which [TitledBorder] promotes to `┬`); each section emits its
 * label adjacent to a single space, with an `─` filler segment that runs
 * up to the separator. The leading section places its fill *after* the
 * label; every following section places it *before*. Result example:
 * `Label1 ─────┬─ Label2`.
 *
 * @param sections each pair is `(label, fillCount)` — the number of `─`
 *                 chars in that section's fill segment.
 */
fun columnHeaderTitle(sections: List<Pair<String, Int>>): String =
    sections.mapIndexed { idx, (label, fill) ->
        val hbars = Symbols.hbar.repeat(fill.coerceAtLeast(0))
        if (idx == 0) "$label $hbars" else "$hbars $label"
    }.joinToString(separator = Symbols.vbar)
