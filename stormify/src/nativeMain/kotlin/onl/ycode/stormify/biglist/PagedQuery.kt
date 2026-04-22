// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.stormify.Stormify
import kotlin.reflect.KClass

/**
 * Stateless, thread-safe paginated query executor for entities of type [T].
 *
 * This is the native (Linux/iOS/macOS) entry point. Construction takes a
 * Kotlin [KClass] — use the reified [PagedQuery] factory for a cleaner call
 * site.
 *
 * Construction does not require a [Stormify] instance — attach one via
 * [Stormify.attach] after construction, or register a [Stormify.defaultInstance]
 * and the query will pick it up on first use.
 */
open class PagedQuery<T : Any>(classType: KClass<T>) : AbstractPagedQuery<T>(classType)

/** Creates a new [PagedQuery] for the reified entity type [T]. */
inline fun <reified T : Any> PagedQuery(): PagedQuery<T> = PagedQuery(T::class)
