package onl.ycode.stormify

import onl.ycode.logger.Logger
import kotlin.reflect.KClass

class TableInfo<T : Any>(
    internal val type: KClass<T>,
    internal val table: String,
    internal val create: () -> T,
    private val fields: (T, String, Any?, Stormify) -> Boolean,
    internal val idNames: List<String>,
    internal val idDbNames: List<String>,
    internal val idTypes: List<KClass<*>>,
    internal val idSequences: List<String>,
    internal val getIdValues: (T) -> List<Any?>,
    internal val restNames: List<String>,
    internal val restDbNames: List<String>,
    internal val restTypes: List<KClass<*>>,
    internal val getRestValues: (T) -> List<Any?>,
    internal val populateQuery: String,
    internal val createQuery: String,
    internal val updateQuery: String,
    internal val deleteQuery: String,
) {
    init {
        require(idNames.size == idDbNames.size) { "The names and db names of the id columns must have the same size" }
        require(idNames.size == idTypes.size) { "The names and types of the id columns must have the same size" }
        require(idNames.size == idSequences.size) { "The names and sequences of the id columns must have the same size" }
        require(restNames.size == restDbNames.size) { "The names and db names of the rest columns must have the same size" }
        require(restNames.size == restTypes.size) { "The names and types of the rest columns must have the same size" }
    }

    private val fieldTypeMap = (idNames.map { it.lowercase() }.zip(idTypes) +
            restNames.map { it.lowercase() }.zip(restTypes)).toMap()

    internal fun getType(name: String): KClass<*> =
        fieldTypeMap[name.lowercase()] ?: throw QueryException("Unknown field $name in $table")

    internal fun setField(entity: T, name: String, value: Any?, stormify: Stormify, errorToLogger: Logger? = null) {
        if (!fields(entity, name, value, stormify)) {
            if (errorToLogger == null) throw QueryException("Unable to set field $name in $table")
            else errorToLogger.error("Unable to set field $name in $table")
        }
    }

    internal val singleKeyName =
        if (idNames.size == 1) idNames[0] else throw QueryException("Multiple primary keys found in $table")

    companion object {
        private val registry = mutableMapOf<KClass<*>, TableInfo<*>>()

        fun register(tableInfo: TableInfo<*>) {
            registry[tableInfo.type] = tableInfo
        }

        @Suppress("UNCHECKED_CAST")
        internal fun <T : Any> retrieve(type: KClass<out T>): TableInfo<T> =
            registry[type] as? TableInfo<T>
                ?: throw IllegalArgumentException("Unknown entity: ${type.simpleName}")
    }
}

