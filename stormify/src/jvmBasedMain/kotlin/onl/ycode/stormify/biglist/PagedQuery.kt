// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.stormify.Stormify
import kotlin.reflect.KClass

/**
 * Stateless, thread-safe paginated query executor for entities of type [T].
 *
 * This is the JVM / Android entry point and provides two constructors:
 * one taking a Kotlin [KClass] (preferred from Kotlin code) and one taking a
 * Java [Class] (preferred from Java code).
 *
 * Construction does not require a [Stormify] instance — attach one via
 * [Stormify.attach] after construction, or register a [Stormify.defaultInstance]
 * and the query will pick it up on first use.
 *
 * See the members below for the full API.
 */
open class PagedQuery<T : Any> : AbstractPagedQuery<T> {
    /** Construct for the given Kotlin class. */
    constructor(classType: KClass<T>) : super(classType)

    /** Construct for the given Java class — the Java-friendly constructor. */
    constructor(entityClass: Class<T>) : super(entityClass.kotlin)
}

/**
 * Creates a new [PagedQuery] for the reified entity type [T].
 *
 * ```kotlin
 * val customers = PagedQuery<Customer>()
 * stormify.attach(customers)
 * ```
 */
inline fun <reified T : Any> PagedQuery(): PagedQuery<T> = PagedQuery(T::class)
