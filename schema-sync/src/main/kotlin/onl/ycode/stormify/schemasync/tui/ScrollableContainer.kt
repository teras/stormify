package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.AbstractInteractableComponent
import com.googlecode.lanterna.gui2.Component
import com.googlecode.lanterna.gui2.Container
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.Interactable
import com.googlecode.lanterna.gui2.InteractableRenderer
import com.googlecode.lanterna.gui2.LayoutManager
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.gui2.TextGUIGraphics
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.input.MouseActionType

/** Anything that can be scrolled by a delta number of lines. Implemented by
 *  [DiffViewer] and [ScrollableContainer] so they share the wheel/key wiring. */
internal interface Scrollable {
    fun scrollBy(delta: Int)
}

/** -1 / +1 when [keyStroke] is a mouse-wheel event, null otherwise. */
internal fun KeyStroke.scrollDelta(rowsPerWheel: Int = 3): Int? {
    val action = this as? MouseAction ?: return null
    return when (action.actionType) {
        MouseActionType.SCROLL_UP -> -rowsPerWheel
        MouseActionType.SCROLL_DOWN -> +rowsPerWheel
        else -> null
    }
}

/**
 * A Panel that vertically clips a single inner component to its own height
 * and exposes [scrollBy] / [ensureVisible] to move that component up/down so
 * the host can keep the focused interactable on screen. Reserves the
 * rightmost column for an interactive scrollbar that handles wheel, click,
 * and drag.
 */
internal class ScrollableContainer(private val inner: Panel) : Panel(), Scrollable {

    private var scrollOffset = 0
    private val bar = ScrollbarWidget()

    init {
        layoutManager = ScrollLayout()
        addComponent(inner)
        addComponent(bar)
    }

    override fun scrollBy(delta: Int) {
        val newOffset = (scrollOffset + delta).coerceIn(0, maxScroll())
        if (newOffset != scrollOffset) {
            scrollOffset = newOffset
            invalidate()
        }
    }

    private fun setScrollOffset(value: Int) {
        val newOffset = value.coerceIn(0, maxScroll())
        if (newOffset != scrollOffset) {
            scrollOffset = newOffset
            invalidate()
        }
    }

    fun ensureVisible(component: Component?) {
        component ?: return
        val targetY = locateWithin(inner, component) ?: return
        val viewportRows = size?.rows ?: return
        if (viewportRows <= 0) return
        val componentRows = component.size?.rows?.takeIf { it > 0 } ?: 1
        when {
            targetY < scrollOffset -> scrollOffset = targetY
            targetY + componentRows > scrollOffset + viewportRows ->
                scrollOffset = (targetY + componentRows - viewportRows).coerceAtLeast(0)
        }
        scrollOffset = scrollOffset.coerceIn(0, maxScroll())
        invalidate()
    }

    private fun maxScroll(): Int {
        val viewportRows = size?.rows ?: return 0
        val contentRows = inner.preferredSize?.rows ?: 0
        return (contentRows - viewportRows).coerceAtLeast(0)
    }

    private fun contentRows(): Int = inner.preferredSize?.rows ?: 0
    private fun viewportRows(): Int = size?.rows ?: 0
    private fun barVisible(): Boolean = contentRows() > viewportRows()

    /** Walks the component tree to find [target]'s y-coordinate inside [root]. */
    private fun locateWithin(root: Container, target: Component): Int? {
        for (child in root.childrenList) {
            if (child === target) return child.position.row
            if (child is Container) {
                val sub = locateWithin(child, target) ?: continue
                return child.position.row + sub
            }
        }
        return null
    }

    private inner class ScrollLayout : LayoutManager {
        private val innerLayout = LinearLayout(Direction.VERTICAL).setSpacing(0)

        override fun getPreferredSize(components: List<Component>): TerminalSize =
            innerLayout.getPreferredSize(components.filter { it !== bar })

        override fun doLayout(area: TerminalSize, components: List<Component>) {
            val showBar = barVisible() && area.columns >= 2
            val innerCols = if (showBar) area.columns - 1 else area.columns
            for (c in components) {
                if (c === bar) continue
                val pref = c.preferredSize ?: continue
                c.size = TerminalSize(innerCols, pref.rows)
                c.position = TerminalPosition(0, -scrollOffset)
            }
            if (showBar) {
                bar.size = TerminalSize(1, area.rows)
                bar.position = TerminalPosition(area.columns - 1, 0)
                bar.isVisible = true
            } else {
                bar.size = TerminalSize(0, 0)
                bar.position = TerminalPosition(area.columns, 0)
                bar.isVisible = false
            }
        }

        override fun hasChanged(): Boolean = false
    }

    /** 1-column-wide interactive scrollbar bound to the parent container. Handles
     *  click on arrows, click on track (jump), and drag. Skipped by Tab traversal
     *  via [nextFocus]/[previousFocus] returning null. */
    private inner class ScrollbarWidget
        : AbstractInteractableComponent<ScrollbarWidget>() {

        private val mouse = ScrollbarMouse()

        override fun handleKeyStroke(keyStroke: KeyStroke): Interactable.Result {
            keyStroke.scrollDelta()?.let { scrollBy(it); return Interactable.Result.HANDLED }
            keyStroke.asMouse?.let { m ->
                val sz = size ?: return Interactable.Result.UNHANDLED
                val local = toLocal(m.position) ?: return Interactable.Result.UNHANDLED
                val newTop = mouse.handle(
                    action = m,
                    localX = local.column,
                    localY = local.row,
                    scrollbarCol = 0,
                    height = sz.rows,
                    contentRows = contentRows(),
                    viewportRows = viewportRows(),
                    currentTop = scrollOffset,
                )
                if (newTop != null) {
                    setScrollOffset(newTop)
                    return Interactable.Result.HANDLED
                }
                if (m.actionType == MouseActionType.DRAG)
                    return Interactable.Result.HANDLED
            }
            return when (keyStroke.keyType) {
                KeyType.ArrowUp -> { scrollBy(-1); Interactable.Result.HANDLED }
                KeyType.ArrowDown -> { scrollBy(+1); Interactable.Result.HANDLED }
                KeyType.PageUp -> { scrollBy(-viewportRows()); Interactable.Result.HANDLED }
                KeyType.PageDown -> { scrollBy(+viewportRows()); Interactable.Result.HANDLED }
                else -> Interactable.Result.UNHANDLED
            }
        }

        override fun createDefaultRenderer(): InteractableRenderer<ScrollbarWidget> =
            object : InteractableRenderer<ScrollbarWidget> {
                override fun getCursorLocation(component: ScrollbarWidget): TerminalPosition? = null
                override fun getPreferredSize(component: ScrollbarWidget): TerminalSize =
                    TerminalSize(1, 1)
                override fun drawComponent(graphics: TextGUIGraphics, component: ScrollbarWidget) {
                    val rows = graphics.size.rows
                    if (rows <= 0 || graphics.size.columns <= 0) return
                    Scrollbar.draw(
                        graphics = graphics,
                        col = 0,
                        height = rows,
                        contentRows = contentRows(),
                        viewportRows = viewportRows(),
                        top = scrollOffset,
                        thumbYOverride = mouse.dragThumbY,
                    )
                }
            }
    }
}

/** Wires window-level Up/Down/PgUp/PgDn to scroll an active [ScrollableContainer]
 *  and tracks focus changes so the focused interactable is always visible. */
internal fun Interactable?.scrollIntoView(scroller: ScrollableContainer) {
    scroller.ensureVisible(this as? Component)
}
