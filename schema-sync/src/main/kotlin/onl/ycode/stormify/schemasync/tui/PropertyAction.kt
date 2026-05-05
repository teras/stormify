package onl.ycode.stormify.schemasync.tui

import onl.ycode.stormify.schemasync.entity.ColumnDelta

/**
 * Per-property pending action. Stored in [PropertyActions] keyed by
 * `tableKey.column`. Default is [NONE] for every kind; the user toggles to
 * [INSERT] with Space inside the middle column.
 *
 * Schema-sync's reduced scope: a binary include/skip per property. INSERT
 * means "make the two sides match" — for ENTITY_ONLY rows that's an
 * `ALTER TABLE ADD COLUMN`, for DB_ONLY rows it's a Kotlin property splice.
 * SYNCED and TYPE_MISMATCH rows are read-only.
 */
enum class PropertyAction {
    /** Skip this row — no migration / no edit. */
    NONE,
    /** Apply the natural sync action for the row's kind. */
    INSERT,
    ;

    companion object {
        fun isTogglable(kind: ColumnDelta.Kind): Boolean = when (kind) {
            ColumnDelta.Kind.ENTITY_ONLY, ColumnDelta.Kind.DB_ONLY -> true
            ColumnDelta.Kind.SYNCED, ColumnDelta.Kind.TYPE_MISMATCH -> false
        }
    }
}

/** Row-kind glyph; ASCII fallback honours `--ascii`. */
fun symbolFor(kind: ColumnDelta.Kind): String {
    val ascii = Symbols.ascii
    return when (kind) {
        ColumnDelta.Kind.TYPE_MISMATCH -> if (ascii) "!" else "⚠"
        ColumnDelta.Kind.SYNCED -> if (ascii) "=" else "═"
        ColumnDelta.Kind.ENTITY_ONLY -> if (ascii) "<" else "◀"
        ColumnDelta.Kind.DB_ONLY -> if (ascii) ">" else "▶"
    }
}

/** Mutable per-property action map keyed by `tableKey.column`, plus the
 *  per-slot set of entity classNames that should receive INSERT splices when
 *  the slot has more than one claim. The default target is the slot's
 *  primary; the user widens the set via the right-pane checkboxes. */
class PropertyActions {
    private val map = mutableMapOf<String, PropertyAction>()
    private val slotTargets = mutableMapOf<String, MutableSet<String>>()

    fun get(tableKey: String, column: String): PropertyAction =
        map["$tableKey.$column"] ?: PropertyAction.NONE

    fun set(tableKey: String, column: String, action: PropertyAction) {
        map["$tableKey.$column"] = action
    }

    fun cycle(tableKey: String, column: String, kind: ColumnDelta.Kind): PropertyAction {
        val current = get(tableKey, column)
        if (!PropertyAction.isTogglable(kind)) return current
        val next = if (current == PropertyAction.NONE) PropertyAction.INSERT else PropertyAction.NONE
        set(tableKey, column, next)
        return next
    }

    /** Returns the set of entity classNames that will receive INSERT splices
     *  for new DB columns of [tableKey]. Initialised lazily to `{primary}` —
     *  but the user is free to clear it entirely afterwards (meaning the
     *  ALTER TABLE happens but no entity gets the new property). */
    fun targetsFor(tableKey: String, primary: String): Set<String> =
        slotTargets.getOrPut(tableKey) { mutableSetOf(primary) }

    /** Toggle membership of [className] in the slot's target set. The set is
     *  allowed to go empty — the primary is only a default seed, not an
     *  invariant. */
    fun toggleTarget(tableKey: String, primary: String, className: String) {
        val set = slotTargets.getOrPut(tableKey) { mutableSetOf(primary) }
        if (className in set) set -= className else set += className
    }

    /** How many properties the user has explicitly toggled (i.e. diverged from
     *  the per-kind default). Used to decide whether quitting deserves a warning. */
    fun userEditCount(): Int = map.size
}
