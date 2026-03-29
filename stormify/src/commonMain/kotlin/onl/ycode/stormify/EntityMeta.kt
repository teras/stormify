// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlin.reflect.KClass

fun interface EntityRegistrar {
    fun register()
}

class PropertyMeta<T : Any>(
    val name: String,
    val type: KClass<*>,
    val isReference: Boolean,
    val getter: (T) -> Any?,
    val setter: (T, Any?, Stormify) -> Unit,
    val dbNameOverride: String?,
    val isPrimaryKey: Boolean,
    val sequence: String?,
    val isCreatable: Boolean = true,
    val isUpdatable: Boolean = true,
    val isTransient: Boolean = false,
)

class EntityMeta<T : Any>(
    val type: KClass<T>,
    val constructor: () -> T,
    val properties: List<PropertyMeta<T>>,
    val tableNameOverride: String?,
) {
    companion object {
        private val registry = mutableMapOf<KClass<*>, EntityMeta<*>>()

        fun register(meta: EntityMeta<*>) {
            registry[meta.type] = meta
        }

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> find(type: KClass<T>): EntityMeta<T>? =
            registry[type] as? EntityMeta<T>
    }
}
