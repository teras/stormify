package onl.ycode.stormify.schemasync.config

import kotlinx.serialization.Serializable
import onl.ycode.stormify.schemasync.model.DefaultsProfile
import onl.ycode.stormify.schemasync.model.KotlinDefaults
import onl.ycode.stormify.schemasync.model.NamingPolicy
import onl.ycode.stormify.schemasync.model.SlotProfile

/**
 * Top-level config root, deserialized from `.schema-sync.toml`. Holds the
 * JDBC connection, slot definitions (one marked `default = true` per
 * category), and the per-dialect DDL templates.
 */
@Serializable
data class SchemaSyncConfig(
    val connection: ConnectionConfig? = null,
    val slots: SlotProfile,
    val defaults: DefaultsProfile = DefaultsProfile(),
    val paths: PathsConfig = PathsConfig(),
    /** Maps Kotlin property names to DB column names. Defaults to stormify's
     *  built-in convention (snake_case). */
    val namingPolicy: NamingPolicy = NamingPolicy.LOWER_CASE_WITH_UNDERSCORES,
    /** Preferences applied when generating Kotlin entity properties from DB columns. */
    val kotlin: KotlinDefaults = KotlinDefaults(),
)

/** Output paths the F2 apply view writes to. Both are relative to the project
 *  directory (the directory holding `.schema-sync.toml`) so the config travels
 *  with the project. */
@Serializable
data class PathsConfig(
    val entitiesDir: String? = null,
    val migrationSql: String? = null,
)

/** JDBC connection coordinates. Persisted in TOML; may be overridden via CLI. */
@Serializable
data class ConnectionConfig(
    val url: String,
    val user: String? = null,
    val password: String? = null,
)
