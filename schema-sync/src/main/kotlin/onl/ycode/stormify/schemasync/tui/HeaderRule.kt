package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.AbstractComponent
import com.googlecode.lanterna.gui2.ComponentRenderer
import com.googlecode.lanterna.gui2.TextGUIGraphics

/**
 * Single-line horizontal rule that fills its full allocated width with hbar
 * characters and places a cross character at the column where the row-level
 * vertical separator sits, so the rule joins seamlessly with the surrounding
 * border on both sides.
 */
class HeaderRule(var crossAt: Int) : AbstractComponent<HeaderRule>() {

    override fun createDefaultRenderer(): ComponentRenderer<HeaderRule> = Renderer()

    private inner class Renderer : ComponentRenderer<HeaderRule> {
        override fun getPreferredSize(component: HeaderRule): TerminalSize =
            TerminalSize(component.crossAt + 2, 1)

        override fun drawComponent(graphics: TextGUIGraphics, component: HeaderRule) {
            val width = graphics.size.columns
            if (width == 0) return
            graphics.applyThemeStyle(component.themeDefinition.normal)
            val hbar = if (Symbols.ascii) '-' else '─'
            val cross = if (Symbols.ascii) '+' else '┼'
            for (x in 0 until width) graphics.setCharacter(x, 0, hbar)
            if (component.crossAt in 0 until width) {
                graphics.setCharacter(component.crossAt, 0, cross)
            }
        }
    }
}
