// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.stormify.Stormify
import kotlin.reflect.KClass

/**
 * Column-based lazy paginated list view of entities of type [T].
 *
 * This is the native (Linux/iOS/macOS) entry point. Construction takes a Kotlin
 * [KClass] — use the reified [PagedList] factory for a cleaner call site.
 *
 * Construction does not require a [Stormify] instance — attach one via
 * [Stormify.attach] after construction, or register a [Stormify.defaultInstance]
 * and the list will pick it up on first use.
 *
 * See [PagedListBase] for the full API (filtering, sorting, pagination, etc.).
 */
class PagedList<T : Any>(classType: KClass<T>) : PagedListBase<T>(classType) {
    companion object {
        /**
         * Global input parser for all PagedList instances. Backed by
         * [PagedListBase.defaultInputParser] — changing either reflects in both.
         * @see InputParser
         */
        var defaultInputParser: InputParser
            get() = PagedListBase.defaultInputParser
            set(value) {
                PagedListBase.defaultInputParser = value
            }

        /**
         * The string representation of a null value. Use this to search for NULL values
         * in a filter instead of using a regular null.
         */
        const val NULL: String = PagedListBase.NULL
    }
}

/**
 * Creates a new [PagedList] for the reified entity type [T].
 */
inline fun <reified T : Any> PagedList(): PagedList<T> = PagedList(T::class)
