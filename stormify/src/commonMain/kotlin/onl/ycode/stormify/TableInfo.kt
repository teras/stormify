// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import onl.ycode.kdbc.SQLException
import onl.ycode.kdbc.TypeConversion
import kotlin.reflect.KClass

/**
 * Describes a single mapped field (property) of an entity, including its database column name,
 * type, and role in CRUD operations.
 *
 * @property name the Kotlin property name
 * @property dbName the corresponding database column name (after [NamingPolicy] conversion)
 * @property type the Kotlin type of the property
 * @property isPrimaryKey whether this field is part of the entity's primary key
 * @property isReference whether this field is a foreign key reference to another entity
 * @property isEnum whether the property type is a Kotlin/Java enum
 * @property enumAsString whether enum values are stored as their string name (`true`) or ordinal/custom integer (`false`)
 * @property sequence the database sequence name used to generate values, or null if not sequence-backed
 * @property isAutoIncrement whether the database auto-generates values for this field (e.g. IDENTITY columns)
 * @property isInsertable whether this field is included in INSERT statements
 * @property isUpdatable whether this field is included in UPDATE statements
 */
data class FieldInfo(
    val name: String,
    val dbName: String,
    val type: KClass<*>,
    val isPrimaryKey: Boolean,
    val isReference: Boolean,
    val isEnum: Boolean,
    val enumAsString: Boolean,
    val sequence: String?,
    val isAutoIncrement: Boolean,
    val isInsertable: Boolean,
    val isUpdatable: Boolean,
)

/**
 * Metadata container for a mapped entity class. Holds the table name, field mappings, primary key
 * information, and pre-built SQL queries for CRUD operations.
 *
 * Instances are created internally by [Stormify] and cached per entity class. Use
 * [Stormify.getTableInfo] to obtain the metadata for a given class.
 *
 * @property tableName the database table name this entity maps to
 */
class TableInfo<T : Any> internal constructor(
    private val meta: EntityMeta<T>,
    private val resolved: List<ResolvedProperty<T>>,
    val tableName: String,
) {
    /** The Kotlin class this metadata describes. */
    val classType: KClass<T> get() = meta.type

    internal fun create(): T = meta.constructor()

    // Resolved property lists
    private val idProps = resolved.filter { it.isPrimaryKey }
    private val restProps = resolved.filter { !it.isPrimaryKey }
    private val insertableProps = resolved.filter { it.isInsertable }
    private val updatableProps = resolved.filter { it.isUpdatable && !it.isPrimaryKey }

    // DB name lookups
    private val fieldTypeMap = resolved.associate { it.dbName.lowercase() to it.type }
    private val fieldByDbName = resolved.groupBy { it.dbName.lowercase() }
    private val referenceFieldMap = resolved.filter { it.isReference }.associate { it.dbName.lowercase() to it.type }
    private val enumFieldSet = resolved.filter { it.isEnum }.mapTo(HashSet()) { it.dbName.lowercase() }

    internal fun getType(dbName: String): KClass<*> =
        fieldTypeMap[dbName.lowercase()] ?: Any::class

    internal fun getScalarType(dbName: String): KClass<*>? =
        fieldTypeMap[dbName.lowercase()]?.takeIf { TypeConversion.isKnownScalar(it) }

    internal fun isReferenceField(dbName: String): Boolean =
        referenceFieldMap.containsKey(dbName.lowercase())

    internal fun getReferenceType(dbName: String): KClass<*>? =
        referenceFieldMap[dbName.lowercase()]

    internal fun isEnumField(dbName: String): Boolean =
        enumFieldSet.contains(dbName.lowercase())

    internal fun setField(
        entity: T,
        dbName: String,
        value: Any?,
        stormify: Stormify,
        policy: UnmatchedColumnPolicy = UnmatchedColumnPolicy.THROW,
    ) {
        val props = fieldByDbName[dbName.lowercase()]
        if (props.isNullOrEmpty()) {
            when (policy) {
                UnmatchedColumnPolicy.THROW -> throw SQLException(
                    "Column $dbName has no matching field in ${meta.type.simpleName}"
                )
                UnmatchedColumnPolicy.WARN -> stormify.logger.warn(
                    "Column $dbName has no matching field in ${meta.type.simpleName}"
                )
                UnmatchedColumnPolicy.IGNORE -> Unit
            }
            return
        }
        for (prop in props) prop.setter(entity, value, stormify)
    }

    /**
     * Returns the resolved properties for the column [dbName], pre-keyed by the lowercased
     * label. Empty list = no matching field on this entity (caller decides the
     * unmatched-column policy). Used by [Stormify]'s populate hot path to cache the property
     * dispatch per-ResultSet so that the per-row inner loop avoids the `dbName.lowercase()`
     * allocation and `fieldByDbName` HashMap lookup on every cell.
     */
    internal fun propertiesForColumn(dbName: String): List<ResolvedProperty<T>> =
        fieldByDbName[dbName.lowercase()] ?: emptyList()

    // ID operations (cached lists — avoid allocation on every call)
    internal val idDbNames = idProps.map { it.dbName }
    internal val idTypes = idProps.map { it.type }
    internal val idSequences = idProps.map { it.sequence ?: "" }

    internal val singleKeyDbName: String by lazy {
        if (idProps.size == 1) idProps[0].dbName
        else throw SQLException("Expected exactly one primary key in $tableName, found ${idProps.size}")
    }

    internal fun getIdValues(entity: T): List<Any?> = idProps.map { it.getter(entity) }

    // SELECT * is intentional; unmatched DB columns go through Stormify.unmatchedColumnPolicy.
    internal val populateQuery by lazy {
        "SELECT * FROM $tableName WHERE ${idProps.joinToString(" AND ") { "${it.dbName} = ?" }}"
    }
    internal val createQuery by lazy {
        val fields = insertableProps.joinToString(", ") { it.dbName }
        val placeholders = insertableProps.joinToString(", ") { "?" }
        "INSERT INTO $tableName ($fields) VALUES ($placeholders)"
    }
    internal val updateQuery by lazy {
        if (updatableProps.isEmpty())
            throw SQLException("Entity $tableName has no updatable fields")
        val setClause = updatableProps.joinToString(", ") { "${it.dbName} = ?" }
        val whereClause = idProps.joinToString(" AND ") { "${it.dbName} = ?" }
        "UPDATE $tableName SET $setClause WHERE $whereClause"
    }
    // Values for create/update queries (in query parameter order)
    internal fun getCreateValues(entity: T): List<Any?> = insertableProps.map { it.sqlValue(entity) }
    internal fun getUpdateValues(entity: T): List<Any?> =
        updatableProps.map { it.sqlValue(entity) } + getIdValues(entity)

    /** All mapped fields of this entity, including primary keys and regular columns. */
    val fieldInfos: List<FieldInfo> by lazy {
        resolved.map {
            FieldInfo(it.name, it.dbName, it.type, it.isPrimaryKey, it.isReference, it.isEnum, it.enumAsString,
                it.sequence, it.isAutoIncrement, it.isInsertable, it.isUpdatable)
        }
    }

    /** The primary key fields of this entity. May contain multiple entries for composite keys. */
    val primaryKeys: List<FieldInfo> get() = fieldInfos.filter { it.isPrimaryKey }

    /** The single primary key field. Throws if the entity has zero or more than one primary key. */
    val primaryKey: FieldInfo
        get() = primaryKeys.singleOrNull()
            ?: throw SQLException("Expected exactly one primary key in $tableName, found ${primaryKeys.size}")

    /** Finds a field by its Kotlin property [name] (case-insensitive), or null if not found. */
    fun getField(name: String): FieldInfo? =
        fieldInfos.find { it.name.equals(name, ignoreCase = true) }

    internal companion object {
        @Suppress("UNCHECKED_CAST")
        internal fun <T : Any> build(
            meta: EntityMeta<T>,
            namingPolicy: (String) -> String,
            blacklist: Set<String>,
            pkResolvers: Collection<(String, String) -> Boolean>
        ): TableInfo<T> {
            val tableName = meta.tableNameOverride?.takeIf { it.isNotBlank() }
                ?: namingPolicy(meta.type.simpleName ?: meta.type.toString())

            val activeProps = meta.properties
                .filter { !it.isTransient && it.name !in blacklist }

            val hasPkAnnotation = activeProps.any { it.isPrimaryKey }

            val resolved = activeProps.map { prop ->
                val dbName = prop.dbNameOverride?.takeIf { it.isNotBlank() }
                    ?: namingPolicy(prop.name)
                val isPk = if (hasPkAnnotation) prop.isPrimaryKey
                else pkResolvers.any { resolver -> resolver(tableName, prop.name) }
                ResolvedProperty(
                    name = prop.name, dbName = dbName, type = prop.type,
                    isReference = prop.isReference, isEnum = prop.isEnum,
                    enumAsString = prop.enumAsString,
                    isPrimaryKey = isPk,
                    sequence = prop.sequence, isAutoIncrement = prop.isAutoIncrement,
                    isInsertable = prop.isCreatable,
                    isUpdatable = prop.isUpdatable,
                    getter = prop.getter, setter = prop.setter
                )
            }

            return TableInfo(meta, resolved, tableName)
        }
    }
}

internal class ResolvedProperty<T : Any>(
    val name: String,
    val dbName: String,
    val type: KClass<*>,
    val isReference: Boolean,
    val isEnum: Boolean,
    val enumAsString: Boolean,
    val isPrimaryKey: Boolean,
    val sequence: String?,
    val isAutoIncrement: Boolean,
    isInsertable: Boolean,
    val isUpdatable: Boolean,
    val getter: (T) -> Any?,
    val setter: (T, Any?, Stormify) -> Unit,
) {
    val isInsertable: Boolean = isInsertable && !isAutoIncrement

    fun sqlValue(entity: T): Any? {
        val value = getter(entity)
        if (value is Enum<*>) return if (enumAsString) value.name else enumToInt(value)
        return value
    }
}
