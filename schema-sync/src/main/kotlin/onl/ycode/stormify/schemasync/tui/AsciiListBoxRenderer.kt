package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.gui2.AbstractListBox
import com.googlecode.lanterna.gui2.ActionListBox
import com.googlecode.lanterna.gui2.TextGUIGraphics

/**
 * ListBox renderer that:
 *  - overdraws the scrollbar column with ASCII characters when `Symbols.ascii`
 *    is on (the default Lanterna scrollbar uses 2-cell ambiguous-width glyphs),
 *  - paints vertical column-separators across the *full* list height for
 *    every column listed in [separatorColumns], so dividers continue below
 *    the last row instead of stopping mid-pane.
 */
class AsciiListBoxRenderer(
    /** Mutable so middle-column lists can re-target separators when nameWidth changes per table. */
    var separatorColumns: List<Int> = emptyList(),
) : AbstractListBox.DefaultListBoxRenderer<Runnable, ActionListBox>() {

    override fun drawComponent(graphics: TextGUIGraphics, listBox: ActionListBox) {
        super.drawComponent(graphics, listBox)

        val width = graphics.size.columns
        val height = graphics.size.rows

        // Full-height column separators (drawn after items so they paint over
        // empty rows below the last item; non-empty rows already contain the
        // same char at the same column).
        if (separatorColumns.isNotEmpty()) {
            val sep = Symbols.vbar[0]
            for (sepCol in separatorColumns) {
                if (sepCol in 0 until width) {
                    for (y in 0 until height) {
                        graphics.setCharacter(sepCol, y, sep)
                    }
                }
            }
        }

        if (!Symbols.ascii) return
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

/**
 * Item renderer that **keeps the selected row visible even when the list has
 * lost focus**, so the user can see "where they were" in the left/middle pane
 * after Tab/arrow navigation. Default Lanterna behaviour renders the selected
 * row identically to any other row when the list is unfocused — there is no
 * visual cue at all. This renderer applies the [getActive] theme style (a
 * dimmer highlight than focused-selected) for the unfocused-selected case.
 */
class PersistentSelectionItemRenderer
    : AbstractListBox.ListItemRenderer<Runnable, ActionListBox>() {

    override fun drawItem(
        graphics: TextGUIGraphics,
        listBox: ActionListBox,
        index: Int,
        item: Runnable,
        selected: Boolean,
        focused: Boolean,
    ) {
        val themeDef = listBox.theme.getDefinition(AbstractListBox::class.java)
        val style = when {
            selected && focused -> themeDef.selected
            selected -> themeDef.active
            else -> themeDef.normal
        }
        graphics.applyThemeStyle(style)

        val width = graphics.size.columns
        var label = TerminalTextUtils.fitString(getLabel(listBox, index, item), width)
        while (TerminalTextUtils.getColumnWidth(label) < width) label += " "
        graphics.putString(0, 0, label)
    }
}
