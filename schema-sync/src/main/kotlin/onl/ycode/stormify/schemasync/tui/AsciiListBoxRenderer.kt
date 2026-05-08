package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.gui2.AbstractListBox
import com.googlecode.lanterna.gui2.ActionListBox
import com.googlecode.lanterna.gui2.CheckBoxList
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

        if (listBox.itemCount <= height || width < 1 || height < 2) return

        // Always overdraw the rightmost column with our own scrollbar so the
        // thumb can pin to the cursor row during drag (Lanterna's default
        // scrollbar back-projects from selectedIndex and lags by one cell on
        // every track click). The dragThumbY is owned by the listbox subclass.
        val dragOverride = (listBox as? SelectionAwareListBox)?.scrollbarMouse?.dragThumbY
        Scrollbar.draw(
            graphics = graphics,
            col = width - 1,
            height = height,
            contentRows = listBox.itemCount,
            viewportRows = 1,
            top = listBox.selectedIndex.coerceAtLeast(0),
            thumbYOverride = dragOverride,
        )
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

        // Reserve the rightmost column for the scrollbar when the list overflows
        // so item text doesn't get overdrawn by the scrollbar glyphs (which would
        // make a click on what looks like text actually hit the scrollbar arrow).
        val totalWidth = graphics.size.columns
        val listRows = listBox.size?.rows ?: 0
        val showsScrollbar = listBox.itemCount > listRows
        val width = if (showsScrollbar) (totalWidth - 1).coerceAtLeast(0) else totalWidth
        if (width <= 0) return
        var label = TerminalTextUtils.fitString(getLabel(listBox, index, item), width)
        while (TerminalTextUtils.getColumnWidth(label) < width) label += " "
        graphics.putString(0, 0, label)
    }
}

/**
 * Item renderer for [CheckBoxList] that downgrades disabled items to plain
 * labels — drops the `[x]` / `[ ]` brackets and renders just the text in the
 * insensitive theme style. Used for the Column status group, which only
 * applies when Both is checked under Table presence; rather than hide the
 * whole section (layout jitter) we keep the rows in place and visually mark
 * them as inert when Both is off. The CheckBoxList also has
 * `setEnabled(false)` set in that state, so Tab traversal skips it and the
 * user's prior selection is preserved for when Both is re-checked.
 */
class InertWhenDisabledCheckBoxRenderer<V>
    : CheckBoxList.CheckBoxListItemRenderer<V>() {

    // Match Lanterna's default getLabel format ("[x] text") so the list's
    // preferred-size calculation reserves room for the brackets too. The
    // base class AbstractListBox.ListItemRenderer.getLabel returns just
    // item.toString(), which would size the list 4 chars short of what
    // drawItem actually paints — manifesting as truncated trailing chars
    // ("Missing Kotlin fie" instead of "Missing Kotlin fields").
    override fun getLabel(listBox: CheckBoxList<V>, index: Int, item: V): String {
        val checked = listBox.isChecked(index) ?: false
        val text = item?.toString() ?: "<null>"
        return if (listBox.isEnabled) {
            "[${if (checked) "x" else " "}] $text"
        } else {
            "    $text" // four leading spaces match the bracketed-row width
        }
    }

    override fun drawItem(
        graphics: TextGUIGraphics,
        listBox: CheckBoxList<V>,
        index: Int,
        item: V,
        selected: Boolean,
        focused: Boolean,
    ) {
        val theme = listBox.theme.getDefinition(CheckBoxList::class.java)
        // CheckBoxList's theme inverts the AbstractListBox conventions: here
        // `active` is the bright blue background and `selected` renders with
        // no visible background (Lanterna's default expects the hardware
        // caret to indicate the focused-list cursor and uses `selected` only
        // for the "where you were" persistent indicator on unfocused lists).
        // The blinking i-beam alone is too easy to miss when the popup has
        // four stacked lists, so we paint the focused-list cursor with the
        // bright `active` style. Unfocused lists show no row highlight at
        // all — the cursor only matters for the list that currently
        // receives keystrokes.
        val itemStyle = when {
            !listBox.isEnabled -> theme.insensitive
            selected && focused -> theme.active
            else -> theme.normal
        }
        graphics.applyThemeStyle(itemStyle)
        graphics.fill(' ')

        val text = item?.toString() ?: "<null>"
        if (listBox.isEnabled) {
            graphics.putString(0, 0, "[ ]")
            val checked = listBox.isChecked(index) ?: false
            graphics.setCharacter(1, 0, if (checked) 'x' else ' ')
        }
        // When disabled, columns 0..2 stay blank — items read as plain labels.
        graphics.putString(4, 0, text)
    }
}
