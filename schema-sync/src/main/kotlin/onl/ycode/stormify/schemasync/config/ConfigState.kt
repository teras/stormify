package onl.ycode.stormify.schemasync.config

/**
 * Mutable config holder shared between TUI components. Every `update` writes
 * the new value through the [ConfigStore] so the project file always reflects
 * what the user sees.
 */
class ConfigState(initial: SchemaSyncConfig, private val store: ConfigStore) {
    var current: SchemaSyncConfig = initial
        private set

    /** Project directory — the parent of `.schema-sync.toml`. Used to resolve
     *  the relative paths stored under [PathsConfig]. */
    val projectDir: java.nio.file.Path get() = store.projectFile.parent
        ?: java.nio.file.Path.of(".").toAbsolutePath().normalize()

    fun update(transform: (SchemaSyncConfig) -> SchemaSyncConfig) {
        val updated = transform(current)
        current = updated
        store.save(updated)
    }
}
