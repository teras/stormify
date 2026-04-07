// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import onl.ycode.kdbc.SQLException
import onl.ycode.logger.Logger
import kotlin.reflect.KClass

data class FieldInfo(
    val name: String,
    val dbName: String,
    val type: KClass<*>,
    val isPrimaryKey: Boolean,
    val isReference: Boolean,
    val sequence: String?,
    val isAutoIncrement: Boolean,
    val isInsertable: Boolean,
    val isUpdatable: Boolean,
)

class TableInfo<T : Any> internal constructor(
    private val meta: EntityMeta<T>,
    private val resolved: List<ResolvedProperty<T>>,
    val tableName: String,
) {
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

    internal fun getType(dbName: String): KClass<*> =
        fieldTypeMap[dbName.lowercase()] ?: Any::class

    internal fun getScalarType(dbName: String): KClass<*>? =
        fieldTypeMap[dbName.lowercase()]?.takeIf { isScalarClass(it) }

    internal fun isReferenceField(dbName: String): Boolean =
        referenceFieldMap.containsKey(dbName.lowercase())

    internal fun getReferenceType(dbName: String): KClass<*>? =
        referenceFieldMap[dbName.lowercase()]

    internal fun setField(entity: T, dbName: String, value: Any?, stormify: Stormify, errorToLogger: Logger? = null) {
        val props = fieldByDbName[dbName.lowercase()]
        if (props.isNullOrEmpty()) {
            if (errorToLogger == null) throw SQLException("Column $dbName has no matching field in ${meta.type.simpleName}")
            else errorToLogger.warn("Column $dbName has no matching field in ${meta.type.simpleName}")
            return
        }
        for (prop in props) prop.setter(entity, value, stormify)
    }

    // ID operations (cached lists — avoid allocation on every call)
    internal val idNames = idProps.map { it.name }
    internal val idDbNames = idProps.map { it.dbName }
    internal val idTypes = idProps.map { it.type }
    internal val idSequences = idProps.map { it.sequence ?: "" }

    internal val singleKeyDbName: String by lazy {
        if (idProps.size == 1) idProps[0].dbName
        else throw SQLException("Expected exactly one primary key in $tableName, found ${idProps.size}")
    }

    internal fun getIdValues(entity: T): List<Any?> = idProps.map { it.getter(entity) }

    // SQL queries (lazy)
    internal val selectFieldNames by lazy {
        resolved.map { it.dbName }.toSet().joinToString(", ")
    }
    internal val populateQuery by lazy {
        "SELECT $selectFieldNames FROM $tableName WHERE ${idProps.joinToString(" AND ") { "${it.dbName} = ?" }}"
    }
    internal val createQuery by lazy {
        val fields = insertableProps.joinToString(", ") { it.dbName }
        val placeholders = insertableProps.joinToString(", ") { "?" }
        "INSERT INTO $tableName ($fields) VALUES ($placeholders)"
    }
    internal val updateQuery by lazy {
        val setClause = updatableProps.joinToString(", ") { "${it.dbName} = ?" }
        val whereClause = idProps.joinToString(" AND ") { "${it.dbName} = ?" }
        "UPDATE $tableName SET $setClause WHERE $whereClause"
    }
    // Values for create/update queries (in query parameter order)
    internal fun getCreateValues(entity: T): List<Any?> = insertableProps.map { it.getter(entity) }
    internal fun getUpdateValues(entity: T): List<Any?> =
        updatableProps.map { it.getter(entity) } + getIdValues(entity)

    // Public introspection API
    val fieldInfos: List<FieldInfo> by lazy {
        resolved.map {
            FieldInfo(it.name, it.dbName, it.type, it.isPrimaryKey, it.isReference,
                it.sequence, it.isAutoIncrement, it.isInsertable, it.isUpdatable)
        }
    }

    val primaryKeys: List<FieldInfo> get() = fieldInfos.filter { it.isPrimaryKey }

    val primaryKey: FieldInfo
        get() = primaryKeys.singleOrNull()
            ?: throw SQLException("Expected exactly one primary key in $tableName, found ${primaryKeys.size}")

    fun getField(name: String): FieldInfo? =
        fieldInfos.find { it.name.equals(name, ignoreCase = true) }

    companion object {
        @Suppress("UNCHECKED_CAST")
        internal fun <T : Any> build(
            meta: EntityMeta<T>,
            namingPolicy: NamingPolicy,
            blacklist: Set<String>,
            pkResolvers: Collection<(String, String) -> Boolean>
        ): TableInfo<T> {
            val tableName = meta.tableNameOverride?.takeIf { it.isNotBlank() }
                ?: namingPolicy.convert(meta.type.simpleName ?: meta.type.toString())

            val activeProps = meta.properties
                .filter { !it.isTransient && it.name !in blacklist }

            val hasPkAnnotation = activeProps.any { it.isPrimaryKey }

            val resolved = activeProps.map { prop ->
                val dbName = prop.dbNameOverride?.takeIf { it.isNotBlank() }
                    ?: namingPolicy.convert(prop.name)
                val isPk = if (hasPkAnnotation) prop.isPrimaryKey
                else pkResolvers.any { resolver -> resolver(tableName, prop.name) }
                ResolvedProperty(
                    name = prop.name, dbName = dbName, type = prop.type,
                    isReference = prop.isReference, isPrimaryKey = isPk,
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
    val isPrimaryKey: Boolean,
    val sequence: String?,
    val isAutoIncrement: Boolean,
    isInsertable: Boolean,
    val isUpdatable: Boolean,
    val getter: (T) -> Any?,
    val setter: (T, Any?, Stormify) -> Unit,
) {
    val isInsertable: Boolean = isInsertable && !isAutoIncrement
}
