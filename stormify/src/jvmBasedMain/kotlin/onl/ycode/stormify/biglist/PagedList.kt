// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.stormify.Stormify
import kotlin.reflect.KClass

/**
 * Facet-based lazy paginated list view of entities of type [T].
 *
 * This is the JVM / Android entry point and provides two constructors:
 * one taking a Kotlin [KClass] (preferred from Kotlin code) and one taking a
 * Java [Class] (preferred from Java code).
 *
 * Construction does not require a [Stormify] instance — attach one via
 * [Stormify.attach] after construction, or register a [Stormify.defaultInstance]
 * and the list will pick it up on first use.
 *
 * See [PagedListBase] for the full API (filtering, sorting, pagination, etc.).
 */
class PagedList<T : Any> : PagedListBase<T> {
    /** Construct for the given Kotlin class. */
    constructor(classType: KClass<T>) : super(classType)

    /** Construct for the given Java class — the Java-friendly constructor. */
    constructor(entityClass: Class<T>) : super(entityClass.kotlin)

    /** Java-facing re-export of [PagedListBase.defaultInputParser]. */
    companion object {
        /**
         * Global input parser for all PagedList instances. Backed by
         * [PagedListBase.defaultInputParser] — changing either reflects in both.
         */
        @JvmStatic
        var defaultInputParser: InputParser?
            get() = PagedListBase.defaultInputParser
            set(value) {
                PagedListBase.defaultInputParser = value
            }
    }
}

/**
 * Creates a new [PagedList] for the reified entity type [T].
 *
 * ```kotlin
 * val list = PagedList<Company>()
 * stormify.attach(list)
 * ```
 */
inline fun <reified T : Any> PagedList(): PagedList<T> = PagedList(T::class)
