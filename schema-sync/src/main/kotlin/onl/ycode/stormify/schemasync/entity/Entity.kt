package onl.ycode.stormify.schemasync.entity

import kotlinx.serialization.Serializable
import onl.ycode.stormify.schemasync.model.SlotCategory
import onl.ycode.stormify.schemasync.model.TypeFamily

/** Wrapper matching the JSON file root. */
@Serializable
data class EntityCatalog(
    val entities: List<KotlinEntity>,
)

/** A discovered Kotlin entity class. Produced by the schema-sync entity plugin or hand-written. */
@Serializable
data class KotlinEntity(
    val className: String,
    val schema: String? = null,
    val table: String,
    val fields: List<EntityField>,
    /** Source file the entity was scanned from (PSI mode); null if loaded from JSON. */
    val sourcePath: String? = null,
) {
    val tableKey: String = listOfNotNull(schema, table).joinToString(".")
}

/** A property/column on a [KotlinEntity]. */
@Serializable
data class EntityField(
    val name: String,
    val column: String,
    val type: String,
    val primaryKey: Boolean = false,
    val nullable: Boolean = true,
    val autoIncrement: Boolean = false,
    val sequence: String = "",
    val creatable: Boolean = true,
    val updatable: Boolean = true,
    /** When non-null, this field is an entity reference (FK). Holds the target entity className. */
    val referencedEntity: String? = null,
    /** Initializer literal text from the source (e.g. "0", "\"\"", "false"). Null when expression is not a safe literal. */
    val defaultLiteral: String? = null,
) {
    val category: SlotCategory? = KotlinTypeMapper.categoryFor(type)
    val family: TypeFamily? = KotlinTypeMapper.familyFor(type)
}
