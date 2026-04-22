// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import onl.ycode.stormify.biglist.AbstractPagedList

/**
 * Interface for objects that can be bound to a [Stormify] instance via [attachTo].
 *
 * Two kinds of library objects implement this:
 * - [StormifyEntity] (and therefore [AutoTable]) — database entities that need to know
 *   which [Stormify] loaded them so they can lazy-load on access.
 * - [PagedList][AbstractPagedList] — column-based paged views that defer their [Stormify] until first use.
 *
 * Call [Stormify.attach] to bind an instance. The target then uses it for subsequent
 * database operations without receiving it as an explicit parameter.
 *
 * User code should not implement this interface directly — extend [StormifyEntity]
 * (for entities) or use the [PagedList][AbstractPagedList] class (for paged views) instead.
 */
interface StormifyAware {
    /**
     * Binds [stormify] to this object. Implementations store the reference and
     * may reset any state that depends on the previously-attached instance.
     *
     * Prefer [Stormify.attach] when you want fluent chaining (`val x = stormify.attach(obj)`).
     */
    fun attachTo(stormify: Stormify)
}
