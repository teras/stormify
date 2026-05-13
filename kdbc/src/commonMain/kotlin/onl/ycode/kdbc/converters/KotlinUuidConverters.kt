// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package onl.ycode.kdbc.converters

import kotlin.reflect.KClass
import kotlin.uuid.Uuid

/**
 * Registers conversions for [kotlin.uuid.Uuid] under two on-wire representations:
 *  - canonical hyphenated text (RFC 4122)
 *  - 16-byte big-endian (matches `Uuid.toByteArray()`).
 */
internal object KotlinUuidConverters {
    fun register(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
        val toUuid = registry.getOrPut(Uuid::class) { mutableMapOf() }
        toUuid[String::class] = { v -> Uuid.parse(v as String) }
        toUuid[ByteArray::class] = { v -> Uuid.fromByteArray(v as ByteArray) }
        registry[String::class]?.put(Uuid::class) { v -> (v as Uuid).toString() }
        registry[ByteArray::class]?.put(Uuid::class) { v -> (v as Uuid).toByteArray() }
    }
}
