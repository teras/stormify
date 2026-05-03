package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.Component
import com.googlecode.lanterna.gui2.Container
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.Interactable
import com.googlecode.lanterna.gui2.LayoutManager
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.input.KeyStroke
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
 * the host can keep the focused interactable on screen.
 */
internal class ScrollableContainer(private val inner: Panel) : Panel(), Scrollable {

    private var scrollOffset = 0

    init {
        layoutManager = ScrollLayout()
        addComponent(inner)
    }

    override fun scrollBy(delta: Int) {
        val newOffset = (scrollOffset + delta).coerceIn(0, maxScroll())
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
        private val inner = LinearLayout(Direction.VERTICAL).setSpacing(0)

        override fun getPreferredSize(components: List<Component>): TerminalSize =
            inner.getPreferredSize(components)

        override fun doLayout(area: TerminalSize, components: List<Component>) {
            inner.doLayout(area, components)
            // Recompute child rect using its own preferred height, then offset.
            for (c in components) {
                val pref = c.preferredSize ?: continue
                c.size = TerminalSize(area.columns, pref.rows)
                c.position = TerminalPosition(0, -scrollOffset)
            }
        }

        override fun hasChanged(): Boolean = false
    }
}

/** Wires window-level Up/Down/PgUp/PgDn to scroll an active [ScrollableContainer]
 *  and tracks focus changes so the focused interactable is always visible. */
internal fun Interactable?.scrollIntoView(scroller: ScrollableContainer) {
    scroller.ensureVisible(this as? Component)
}
