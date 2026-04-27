package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.gui2.AbstractListBox
import com.googlecode.lanterna.gui2.ActionListBox
import com.googlecode.lanterna.gui2.TextGUIGraphics

/**
 * ListBox renderer that overdraws the scrollbar with ASCII characters when
 * `Symbols.ascii` is on. Delegates to the default Lanterna renderer for items
 * and base layout, then redraws only the scrollbar column.
 */
class AsciiListBoxRenderer : AbstractListBox.DefaultListBoxRenderer<Runnable, ActionListBox>() {

    override fun drawComponent(graphics: TextGUIGraphics, listBox: ActionListBox) {
        super.drawComponent(graphics, listBox)
        if (!Symbols.ascii) return

        val width = graphics.size.columns
        val height = graphics.size.rows
        if (listBox.itemCount <= height || width < 1 || height < 2) return

        val col = width - 1
        graphics.setCharacter(col, 0, '^')
        graphics.setCharacter(col, height - 1, 'v')
        for (y in 1 until height - 1) {
            graphics.setCharacter(col, y, '|')
        }
        val selected = listBox.selectedIndex
        if (selected >= 0 && listBox.itemCount > 1) {
            val thumbY = ((selected.toDouble() / (listBox.itemCount - 1)) * (height - 3) + 1).toInt()
                .coerceIn(1, height - 2)
            graphics.setCharacter(col, thumbY, '#')
        }
    }
}
