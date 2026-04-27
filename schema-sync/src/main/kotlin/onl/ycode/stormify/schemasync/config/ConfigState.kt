package onl.ycode.stormify.schemasync.config

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
}
