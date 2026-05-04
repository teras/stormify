package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.gui2.Component
import com.googlecode.lanterna.gui2.TextGUIGraphics
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.input.MouseActionType

/**
 * Vertical scrollbar drawing + mouse handling shared by every scrollable widget
 * in schema-sync. The scrollbar is laid out as: row 0 = up arrow, rows 1..H-2 =
 * track, row H-1 = down arrow. The thumb is a single cell positioned
 * proportionally to `top / maxTop`. When `maxTop == 0` no thumb is drawn.
 */
internal object Scrollbar {

    /** True while the user is mid-drag and we should keep mapping y → top. */
    fun draw(
        graphics: TextGUIGraphics,
        col: Int,
        height: Int,
        contentRows: Int,
        viewportRows: Int,
        top: Int,
        thumbYOverride: Int? = null,
    ) {
        if (height < 2 || col < 0 || col >= graphics.size.columns) return
        val ascii = Symbols.ascii
        val up = if (ascii) '^' else '▲'
        val down = if (ascii) 'v' else '▼'
        val track = if (ascii) '|' else '░'
        val thumb = if (ascii) '#' else '█'

        graphics.setCharacter(col, 0, up)
        graphics.setCharacter(col, height - 1, down)
        for (y in 1 until height - 1) graphics.setCharacter(col, y, track)

        val maxTop = (contentRows - viewportRows).coerceAtLeast(0)
        if (maxTop == 0 || height < 4) return
        val trackLen = height - 2
        val thumbY = thumbYOverride
            ?.coerceIn(1, height - 2)
            ?: (1 + (top.toLong() * (trackLen - 1) / maxTop).toInt().coerceIn(0, trackLen - 1))
        graphics.setCharacter(col, thumbY, thumb)
    }

    /** Maps a click/drag y-coordinate (component-local) to the corresponding
     *  top row, given content/viewport sizes. Returns null if the y is outside
     *  the track or the content fits entirely. */
    fun trackToTop(y: Int, height: Int, contentRows: Int, viewportRows: Int): Int? {
        if (height < 4) return null
        val maxTop = (contentRows - viewportRows).coerceAtLeast(0)
        if (maxTop == 0) return null
        val trackLen = height - 2
        val rel = (y - 1).coerceIn(0, trackLen - 1)
        return (rel.toLong() * maxTop / (trackLen - 1).coerceAtLeast(1))
            .toInt().coerceIn(0, maxTop)
    }
}

/**
 * Per-widget state machine for scrollbar mouse interaction. Owns the
 * "drag in progress" flag so subsequent DRAG events are mapped to top
 * positions even when the cursor leaves the scrollbar column.
 */
internal class ScrollbarMouse {
    private var dragging = false

    /** Cursor row pinned by the most recent track press/drag, used by the
     *  renderer to draw the thumb exactly under the user's cursor while
     *  dragging. Cleared on release so the thumb returns to its
     *  proportionally back-projected position from `top`. */
    var dragThumbY: Int? = null
        private set

    /**
     * Resolve a mouse action on the scrollbar column to a new top row.
     *
     *  - press on up arrow → top - 1
     *  - press on down arrow → top + 1
     *  - press on track → jump to that position (and start dragging)
     *  - drag → continue mapping y to top
     *  - release → stop dragging
     *
     * Returns the new top if the action mutated state, null otherwise.
     */
    fun handle(
        action: MouseAction,
        localX: Int,
        localY: Int,
        scrollbarCol: Int,
        height: Int,
        contentRows: Int,
        viewportRows: Int,
        currentTop: Int,
    ): Int? {
        val maxTop = (contentRows - viewportRows).coerceAtLeast(0)
        return when (action.actionType) {
            MouseActionType.CLICK_DOWN -> {
                if (localX != scrollbarCol) return null
                if (localY !in 0 until height) return null
                when (localY) {
                    0 -> { dragThumbY = null; (currentTop - 1).coerceIn(0, maxTop) }
                    height - 1 -> { dragThumbY = null; (currentTop + 1).coerceIn(0, maxTop) }
                    else -> {
                        dragging = true
                        val clamped = localY.coerceIn(1, height - 2)
                        dragThumbY = clamped
                        Scrollbar.trackToTop(clamped, height, contentRows, viewportRows) ?: currentTop
                    }
                }
            }
            MouseActionType.DRAG -> {
                if (!dragging) return null
                val clamped = localY.coerceIn(1, height - 2)
                dragThumbY = clamped
                Scrollbar.trackToTop(clamped, height, contentRows, viewportRows)
            }
            MouseActionType.CLICK_RELEASE -> {
                dragging = false
                dragThumbY = null
                null
            }
            else -> null
        }
    }
}

/** True if [keyStroke] is any kind of mouse event (click/drag/wheel). */
internal val KeyStroke.asMouse: MouseAction? get() = this as? MouseAction

/** Component-local coordinates of [global], or null if [component] has no
 *  resolved position yet (pre-layout). */
internal fun Component.toLocal(global: TerminalPosition): TerminalPosition? {
    val origin = toGlobal(TerminalPosition.TOP_LEFT_CORNER) ?: return null
    return TerminalPosition(global.column - origin.column, global.row - origin.row)
}
