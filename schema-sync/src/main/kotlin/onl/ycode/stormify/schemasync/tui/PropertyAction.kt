package onl.ycode.stormify.schemasync.tui

import onl.ycode.stormify.schemasync.entity.ColumnDelta

/**
 * Per-property pending action. Stored in [PropertyActions] keyed by
 * `tableKey.column`. Default is computed from the column's [ColumnDelta.Kind]
 * via [defaultFor]; the user can cycle with Space inside the middle column.
 *
 * Schema-sync's reduced scope: only insert / delete — no type or size changes.
 * On the entity side "delete" is implemented as `@Transient` (non-destructive);
 * on the DB side it's `DROP COLUMN`. The UI exposes both as a single "delete"
 * concept with the same `x` symbol — the per-side mechanism is internal.
 *
 * TYPE_MISMATCH rows are warning-only — no action available.
 */
enum class PropertyAction {
    /** Skip this row — no migration / no edit. */
    NONE,
    /** Add the missing column to the side that lacks it. */
    INSERT,
    /** Remove the column. On DB-side rows: `DROP COLUMN`. On entity-side rows: `@Transient`. */
    DELETE,
    ;

    companion object {
        /** Default action when a row is first shown. */
        fun defaultFor(kind: ColumnDelta.Kind): PropertyAction = when (kind) {
            ColumnDelta.Kind.SYNCED -> NONE
            ColumnDelta.Kind.ENTITY_ONLY -> INSERT
            ColumnDelta.Kind.DB_ONLY -> INSERT
            ColumnDelta.Kind.TYPE_MISMATCH -> NONE
        }

        /**
         * Cycle order shown to the user when Space is pressed. Only one-sided
         * rows have a cycle; SYNCED and TYPE_MISMATCH are read-only. Use a
         * separate "clear" binding to reset to [NONE] (skip/no-op).
         */
        fun cycleOrder(kind: ColumnDelta.Kind): List<PropertyAction> = when (kind) {
            ColumnDelta.Kind.ENTITY_ONLY -> listOf(INSERT, DELETE)
            ColumnDelta.Kind.DB_ONLY -> listOf(INSERT, DELETE)
            ColumnDelta.Kind.SYNCED, ColumnDelta.Kind.TYPE_MISMATCH -> emptyList()
        }
    }
}

/** Single-character pending-action symbol. ASCII fallback for `--ascii` mode. */
fun symbolFor(kind: ColumnDelta.Kind, action: PropertyAction): String {
    val ascii = Symbols.ascii
    return when (kind) {
        ColumnDelta.Kind.TYPE_MISMATCH -> if (ascii) "!" else "⚠"
        ColumnDelta.Kind.SYNCED -> if (ascii) "=" else "═"
        ColumnDelta.Kind.ENTITY_ONLY -> when (action) {
            PropertyAction.INSERT -> if (ascii) "<" else "◀"
            PropertyAction.DELETE -> if (ascii) "x" else "✗"
            else -> if (ascii) "." else "·"
        }
        ColumnDelta.Kind.DB_ONLY -> when (action) {
            PropertyAction.INSERT -> if (ascii) ">" else "▶"
            PropertyAction.DELETE -> if (ascii) "x" else "✗"
            else -> if (ascii) "." else "·"
        }
    }
}

/** Mutable per-property action map keyed by `tableKey.column`, plus the
 *  per-slot set of entity classNames that should receive INSERT splices when
 *  the slot has more than one claim. The default target is the slot's
 *  primary; the user widens the set via the right-pane checkboxes. */
class PropertyActions {
    private val map = mutableMapOf<String, PropertyAction>()
    private val slotTargets = mutableMapOf<String, MutableSet<String>>()

    fun get(tableKey: String, column: String, kind: ColumnDelta.Kind): PropertyAction =
        map["$tableKey.$column"] ?: PropertyAction.defaultFor(kind)

    fun set(tableKey: String, column: String, action: PropertyAction) {
        map["$tableKey.$column"] = action
    }

    fun cycle(tableKey: String, column: String, kind: ColumnDelta.Kind): PropertyAction {
        val order = PropertyAction.cycleOrder(kind)
        if (order.isEmpty()) return get(tableKey, column, kind)
        val current = get(tableKey, column, kind)
        val idx = order.indexOf(current)
        val next = order[(idx + 1).mod(order.size)]
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
