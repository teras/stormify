// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

@PublishedApi
internal actual class LazyDetailsProperty<T : Any> actual constructor(
    private val cls: KClass<T>,
    private val propertyName: String
) : ReadWriteProperty<Any?, List<T>> {
    private var initialized = false
    private var value: List<T> = emptyList()

    actual override fun getValue(thisRef: Any?, property: KProperty<*>): List<T> {
        if (!initialized) {
            // Marked only once loaded: a failed query leaves it to be retried.
            value = loadLazyDetails(thisRef, cls, propertyName)
            initialized = true
        }
        return value
    }

    actual override fun setValue(thisRef: Any?, property: KProperty<*>, value: List<T>) {
        initialized = true
        this.value = value
    }
}
