// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import kotlin.reflect.KClass

/**
 * Single source of truth for `KClass` → [Facet.Type] classification. Common
 * Kotlin types register here; platform-specific (`java.time.*`, `java.sql.*`,
 * `java.math.*`, kotlinx-datetime, ionspin) are added through
 * [registerPlatformScalars]. Consumers — [Facet] auto-detection in
 * `PagedQueryCore.detectType`, textual-class check in `DefaultDataConverter` —
 * all read from the same map, so adding a new type is a single-line change.
 */
internal object ScalarTypes {
    private val map = mutableMapOf<KClass<*>, Facet.Type>()

    init {
        register(Facet.Type.TEXT, String::class, Char::class, StringBuilder::class, CharArray::class)
        register(Facet.Type.NUMERIC, Byte::class, Short::class, Int::class, Long::class, Float::class, Double::class)
        registerPlatformScalars()
    }

    internal fun register(category: Facet.Type, vararg types: KClass<*>) {
        for (t in types) map[t] = category
    }

    fun categoryOf(type: KClass<*>): Facet.Type? = map[type]
}

internal expect fun ScalarTypes.registerPlatformScalars()
