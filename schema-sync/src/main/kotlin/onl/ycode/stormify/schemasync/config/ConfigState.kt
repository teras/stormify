package onl.ycode.stormify.schemasync.config

import onl.ycode.stormify.schemasync.model.SlotCategory

/**
 * Mutable config holder shared between TUI components. Every `update` writes
 * the new value through the [ConfigStore] so the project file always reflects
 * what the user sees.
 */
class ConfigState(initial: SchemaSyncConfig, private val store: ConfigStore) {
    var current: SchemaSyncConfig = initial
        private set

    fun update(transform: (SchemaSyncConfig) -> SchemaSyncConfig) {
        val updated = transform(current)
        current = updated
        store.save(updated)
    }

    /** Add or replace the assignment for a column key (`table.column` or `schema.table.column`). */
    fun setAssignment(columnKey: String, category: SlotCategory, slot: String) {
        update { cfg ->
            val others = cfg.assignments.filterNot { it.column == columnKey }
            cfg.copy(assignments = others + Assignment(columnKey, category, slot))
        }
    }
}
