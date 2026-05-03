package onl.ycode.stormify.schemasync.entity

import onl.ycode.stormify.schemasync.model.SlotCategory
import onl.ycode.stormify.schemasync.model.TypeFamily

/** A discovered Kotlin entity class produced by [source.EntityScanner]. */
data class KotlinEntity(
    val className: String,
    val schema: String? = null,
    val table: String,
    val fields: List<EntityField>,
    /** Source file the entity was scanned from. */
    val sourcePath: String? = null,
    /** Fully-qualified imports declared in the source file. Used to disambiguate
     *  same-simple-name types (e.g. java.time.Instant vs kotlinx.datetime.Instant). */
    val imports: List<String> = emptyList(),
    /** True when the class declaration carries an explicit supertype/extends
     *  clause; the F2 ByDb-retrofit pass only touches entities without one. */
    val hasSuperType: Boolean = false,
    /** True when the table name comes from an explicit `@DbTable(name=…)` /
     *  `@Table(name=…)` — false when it was derived from the class name via
     *  the active naming policy. Drives the multi-entity primary tiebreaker. */
    val explicitTableName: Boolean = false,
) {
    val tableKey: String = listOfNotNull(schema, table).joinToString(".")
}

/** A property/column on a [KotlinEntity]. */
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
    /** True when scanned from the primary constructor's val/var parameters,
     *  false when declared in the class body. Drives style detection for new
     *  properties we add via F2 Apply. */
    val inConstructor: Boolean = true,
    /** Only set for body-declared fields: true when the field is preceded by
     *  one or more blank lines in the source. Drives whether new body-inserted
     *  properties should themselves get a leading blank line. */
    val precedingBlankLine: Boolean? = null,
) {
    val category: SlotCategory? = KotlinTypeMapper.categoryFor(type)
    val family: TypeFamily? = KotlinTypeMapper.familyFor(type)
}
