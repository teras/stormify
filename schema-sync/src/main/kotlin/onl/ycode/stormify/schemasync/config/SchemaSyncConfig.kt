package onl.ycode.stormify.schemasync.config

import kotlinx.serialization.Serializable
import onl.ycode.stormify.schemasync.model.DefaultsProfile
import onl.ycode.stormify.schemasync.model.SlotCategory
import onl.ycode.stormify.schemasync.model.SlotProfile

/** Top-level config root, deserialized from `.schema-sync.toml`. */
@Serializable
data class SchemaSyncConfig(
    val connection: ConnectionConfig? = null,
    val slots: SlotProfile,
    val seeds: Seeds,
    val defaults: DefaultsProfile = DefaultsProfile(),
    val assignments: List<Assignment> = emptyList(),
)

/** JDBC connection coordinates. Persisted in TOML; may be overridden via CLI. */
@Serializable
data class ConnectionConfig(
    val url: String,
    val user: String? = null,
    val password: String? = null,
)

@Serializable
data class SeedEntry(
    val slot: String,
    val words: List<String>,
)

/** Seed vocabularies grouped by slot category. Each entry is `(slot, words)`. */
@Serializable
data class Seeds(
    val text: List<SeedEntry>,
    val integral: List<SeedEntry>,
    val decimal: List<SeedEntry>,
)

/**
 * A user's classification choice, persisted across runs. The `column` key is
 * `schema.table.column` (or `table.column` when no schema). The classifier
 * is re-trained from these on every startup so suggestions improve over time
 * within the project.
 */
@Serializable
data class Assignment(
    val column: String,
    val category: SlotCategory,
    val slot: String,
)
