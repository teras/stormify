package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.Component
import com.googlecode.lanterna.gui2.LayoutManager

/**
 * Custom layout for the schema-sync main row. Treats four logical areas
 * (Table column, Entity column, Property pane, Diff pane) with priority-
 * based continuous shrinking.
 *
 * Shrink order, lowest priority first: diff (4) → property (3) → entity
 * column (2) → table column (1). Each area shrinks toward a [MIN] cell
 * sliver before being dropped (size 0). Diff has no preferred width — it
 * absorbs the surplus when there is one and shrinks toward [MIN] otherwise.
 *
 * Component ordering passed to this layout MUST be exactly:
 *   [leftPane, propsPane, diffPane]
 */
class PriorityShrinkLayout(
    private val tablePref: () -> Int,
    private val entityPref: () -> Int,
    private val midPref: () -> Int,
    /** Outer (bordered) width of the left pane for arbitrary sub-widths. */
    private val leftOuterWidth: (table: Int, entity: Int) -> Int,
    /** Notified each pass with the chosen Table / Entity sub-widths. */
    private val onLeftDisplayChange: (table: Int, entity: Int) -> Unit,
) : LayoutManager {

    private val MIN = 2
    private var changed = true
    private var lastT1 = -1
    private var lastT2 = -1

    fun invalidate() { changed = true }

    override fun hasChanged(): Boolean = changed

    override fun getPreferredSize(components: List<Component>): TerminalSize {
        val rows = components.maxOfOrNull { it.preferredSize.rows } ?: 0
        val cols = leftOuterWidth(tablePref(), entityPref()) + midPref() + 10
        return TerminalSize(cols, rows)
    }

    override fun doLayout(area: TerminalSize, components: List<Component>) {
        changed = false
        if (components.size < 3) return
        val left = components[0]
        val middle = components[1]
        val right = components[2]

        val W = area.columns
        val H = area.rows

        val pref1 = tablePref()
        val pref2 = entityPref()
        val pref3 = midPref()

        // Start at preferred for areas 1+2+3 with diff pinned at the MIN sliver
        // (a visible cue that the pane exists). If there's headroom, diff is
        // promoted to absorb the surplus.
        var t1 = pref1
        var t2 = pref2
        var t3 = pref3
        var t4 = MIN

        val leftOuter = { l1: Int, l2: Int -> leftOuterWidth(l1, l2) }
        val totalAtPref = leftOuter(t1, t2) + t3 + t4

        if (W >= totalAtPref) {
            // Surplus → diff
            t4 = W - leftOuter(t1, t2) - t3
        } else {
            var deficit = totalAtPref - W

            // Each step shrinks the lowest-surviving-priority area toward MIN
            // before fully dropping it. Cells freed by a drop reduce deficit.

            // 1. Shrink mid (3) from preferred down to MIN.
            val s3 = minOf(deficit, pref3 - MIN).coerceAtLeast(0)
            t3 -= s3; deficit -= s3

            // 2. Drop diff (4) sliver.
            if (deficit > 0) { t4 = 0; deficit -= MIN }

            // 3. Drop mid (3) sliver.
            if (deficit > 0) { t3 = 0; deficit -= MIN }

            // 4. Shrink entity column (2) from preferred down to MIN.
            //    1 cell of t2 reduction frees exactly 1 outer cell (the
            //    leftBordered formula is linear in t2 while both > 0).
            if (deficit > 0) {
                val s2 = minOf(deficit, pref2 - MIN).coerceAtLeast(0)
                t2 -= s2; deficit -= s2
            }

            // 5. Drop entity column (2) sliver: leftBordered(t1,2)→leftBordered(t1,0)
            //    frees 5 cells (the divider+padding+sliver collapse together).
            if (deficit > 0) { t2 = 0; deficit -= 5 }

            // 6. Shrink table column (1) from preferred down to MIN.
            if (deficit > 0) {
                val s1 = minOf(deficit, pref1 - MIN).coerceAtLeast(0)
                t1 -= s1; deficit -= s1
            }

            // 7. Drop table column (1) sliver: leftBordered(2,0)→leftBordered(0,0)
            //    frees 6 cells (badge+padding+sliver collapse to bare 2-cell border).
            if (deficit > 0) { t1 = 0; deficit -= 6 }

            // If deficit still positive, the window is so narrow that even
            // the bare-border sliver doesn't fit. The components below will
            // be placed at size (0, 0) which means "draw nothing".
        }

        // Coerce to non-negative; the deficit math may have over-shot when
        // dropping a pane that was at MIN (releases MIN cells even when only
        // 1 was needed). Surplus is left as a gap on the right edge.
        if (t1 < 0) t1 = 0
        if (t2 < 0) t2 = 0
        if (t3 < 0) t3 = 0
        if (t4 < 0) t4 = 0

        if (t1 != lastT1 || t2 != lastT2) {
            lastT1 = t1; lastT2 = t2
            onLeftDisplayChange(t1, t2)
        }

        val leftW = if (t1 == 0 && t2 == 0 && deficitWasUnsatisfied(W)) 0 else leftOuter(t1, t2)
        // We use deficitWasUnsatisfied as a marker for "window is too narrow
        // for even a sliver" — in that case the pane disappears entirely.

        place(left,  0,                leftW, H)
        place(middle, leftW,            t3,    H)
        place(right,  leftW + t3,       t4,    H)
    }

    /** Whether the requested width was below the bare-sliver minimum (2 cells). */
    private fun deficitWasUnsatisfied(W: Int): Boolean = W < MIN

    private fun place(c: Component, x: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) {
            c.position = TerminalPosition(0, 0)
            c.size = TerminalSize(0, 0)
        } else {
            c.position = TerminalPosition(x, 0)
            c.size = TerminalSize(w, h)
        }
    }
}
