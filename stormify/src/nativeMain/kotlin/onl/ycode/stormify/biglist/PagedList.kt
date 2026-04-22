// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.stormify.Stormify
import kotlin.reflect.KClass

/**
 * Facet-based lazy paginated list view of entities of type [T].
 *
 * This is the native (Linux/iOS/macOS) entry point. Construction takes a Kotlin
 * [KClass] — use the reified [PagedList] factory for a cleaner call site.
 *
 * Construction does not require a [Stormify] instance — attach one via
 * [Stormify.attach] after construction, or register a [Stormify.defaultInstance]
 * and the list will pick it up on first use.
 */
open class PagedList<T : Any>(classType: KClass<T>) : AbstractPagedList<T>(classType)

/**
 * Creates a new [PagedList] for the reified entity type [T].
 */
inline fun <reified T : Any> PagedList(): PagedList<T> = PagedList(T::class)
