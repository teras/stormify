package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.AbstractBorder
import com.googlecode.lanterna.gui2.Border
import com.googlecode.lanterna.gui2.Component
import com.googlecode.lanterna.gui2.ComponentRenderer
import com.googlecode.lanterna.gui2.TextGUIGraphics

/**
 * ASCII-only titled border using `+-|` characters. Used as drop-in replacement
 * for Lanterna's `Borders.singleLine(title)` when running in `--ascii` mode.
 */
class AsciiBorder(private val title: String) : AbstractBorder() {

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

            graphics.setCharacter(0, 0, '+')
            graphics.setCharacter(w - 1, 0, '+')
            graphics.setCharacter(0, h - 1, '+')
            graphics.setCharacter(w - 1, h - 1, '+')
            for (x in 1 until w - 1) {
                graphics.setCharacter(x, 0, '-')
                graphics.setCharacter(x, h - 1, '-')
            }
            for (y in 1 until h - 1) {
                graphics.setCharacter(0, y, '|')
                graphics.setCharacter(w - 1, y, '|')
            }

            if (title.isNotEmpty() && w >= title.length + 4) {
                graphics.putString(2, 0, " $title ")
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

/** Wraps a component with the appropriate border style for the current Symbols mode. */
fun Component.withTitledBorder(title: String): Component =
    if (Symbols.ascii) {
        AsciiBorder(title).apply { component = this@withTitledBorder }
    } else {
        this.withBorder(com.googlecode.lanterna.gui2.Borders.singleLine(title))
    }
