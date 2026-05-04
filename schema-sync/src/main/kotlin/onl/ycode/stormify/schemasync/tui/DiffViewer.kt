package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.gui2.AbstractInteractableComponent
import com.googlecode.lanterna.gui2.Interactable
import com.googlecode.lanterna.gui2.InteractableRenderer
import com.googlecode.lanterna.gui2.TextGUIGraphics
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType

/** A single line in [DiffViewer]; [color] = null uses the theme's default. */
data class DiffLine(val text: String, val color: TextColor? = null)

/**
 * Read-only scrollable component that renders [DiffLine]s using each line's
 * own foreground colour but the theme's default background, so it doesn't
 * inherit the active/focused colour pair Lanterna's TextBox would apply.
 */
class DiffViewer(initialLines: List<DiffLine> = emptyList())
    : AbstractInteractableComponent<DiffViewer>(), Scrollable {

    private var lines: List<DiffLine> = initialLines
    private var topRow: Int = 0
    private val scrollbarMouse = ScrollbarMouse()

    fun setLines(newLines: List<DiffLine>) {
        lines = newLines
        topRow = 0
        invalidate()
    }

    private fun visibleRows(): Int = size?.rows?.coerceAtLeast(1) ?: 1

    private fun maxTop(): Int = (lines.size - visibleRows()).coerceAtLeast(0)

    private fun showsScrollbar(): Boolean = lines.size > visibleRows()

    override fun scrollBy(delta: Int) {
        val nt = (topRow + delta).coerceIn(0, maxTop())
        if (nt != topRow) {
            topRow = nt
            invalidate()
        }
    }

    private fun setTop(newTop: Int) {
        val nt = newTop.coerceIn(0, maxTop())
        if (nt != topRow) {
            topRow = nt
            invalidate()
        }
    }

    override fun handleKeyStroke(keyStroke: KeyStroke): Interactable.Result {
        keyStroke.scrollDelta()?.let { scrollBy(it); return Interactable.Result.HANDLED }
        keyStroke.asMouse?.let { m ->
            if (handleMouse(m)) return Interactable.Result.HANDLED
        }
        return when (keyStroke.keyType) {
            KeyType.ArrowUp -> { scrollBy(-1); Interactable.Result.HANDLED }
            KeyType.ArrowDown -> { scrollBy(+1); Interactable.Result.HANDLED }
            KeyType.PageUp -> { scrollBy(-visibleRows()); Interactable.Result.HANDLED }
            KeyType.PageDown -> { scrollBy(+visibleRows()); Interactable.Result.HANDLED }
            KeyType.Home -> { topRow = 0; invalidate(); Interactable.Result.HANDLED }
            KeyType.End -> { topRow = maxTop(); invalidate(); Interactable.Result.HANDLED }
            else -> super.handleKeyStroke(keyStroke)
        }
    }

    private fun handleMouse(action: com.googlecode.lanterna.input.MouseAction): Boolean {
        val sz = size ?: return false
        if (!showsScrollbar()) return false
        val local = toLocal(action.position) ?: return false
        val newTop = scrollbarMouse.handle(
            action = action,
            localX = local.column,
            localY = local.row,
            scrollbarCol = sz.columns - 1,
            height = sz.rows,
            contentRows = lines.size,
            viewportRows = sz.rows,
            currentTop = topRow,
        ) ?: return action.actionType == com.googlecode.lanterna.input.MouseActionType.DRAG
        setTop(newTop)
        return true
    }

    override fun createDefaultRenderer(): InteractableRenderer<DiffViewer> = DiffViewerRenderer()

    fun lines(): List<DiffLine> = lines
    fun topRow(): Int = topRow
    fun thumbYOverride(): Int? = scrollbarMouse.dragThumbY

    private class DiffViewerRenderer : InteractableRenderer<DiffViewer> {
        override fun getCursorLocation(component: DiffViewer): TerminalPosition? = null

        override fun getPreferredSize(component: DiffViewer): TerminalSize {
            val widest = component.lines().maxOfOrNull { it.text.length } ?: 0
            return TerminalSize(widest.coerceAtLeast(40), component.lines().size.coerceAtLeast(5))
        }

        override fun drawComponent(graphics: TextGUIGraphics, component: DiffViewer) {
            val themeDef = component.themeDefinition
            graphics.applyThemeStyle(themeDef.normal)
            graphics.fill(' ')
            val rows = graphics.size.rows
            val cols = graphics.size.columns
            val lines = component.lines()
            val showsBar = lines.size > rows
            val textCols = if (showsBar) (cols - 1).coerceAtLeast(0) else cols
            for (i in 0 until rows) {
                val idx = component.topRow() + i
                if (idx >= lines.size) break
                val line = lines[idx]
                graphics.applyThemeStyle(themeDef.normal)
                if (line.color != null) {
                    graphics.setForegroundColor(line.color)
                    graphics.enableModifiers(SGR.BOLD)
                }
                val text = line.text.let { if (it.length > textCols) it.substring(0, textCols) else it }
                graphics.putString(0, i, text)
                if (line.color != null) graphics.disableModifiers(SGR.BOLD)
            }
            if (showsBar && cols >= 1) {
                graphics.applyThemeStyle(themeDef.normal)
                Scrollbar.draw(
                    graphics = graphics,
                    col = cols - 1,
                    height = rows,
                    contentRows = lines.size,
                    viewportRows = rows,
                    top = component.topRow(),
                    thumbYOverride = component.thumbYOverride(),
                )
            }
        }
    }
}
