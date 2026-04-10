// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * Marker interface for objects that can hold a reference to a [Stormify] instance.
 *
 * Two kinds of library objects implement this:
 * - [StormifyEntity] (and therefore [AutoTable]) — database entities that need to know
 *   which [Stormify] loaded them so they can lazy-load on access.
 * - `PagedList` — column-based paged views that defer their [Stormify] until first use.
 *
 * Call [Stormify.attach] to bind an instance. The target then uses it for subsequent
 * database operations without receiving it as an explicit parameter.
 *
 * User code should not implement this interface directly — extend [StormifyEntity]
 * (for entities) or use the `PagedList` class (for paged views) instead.
 */
interface StormifyAware {
    /**
     * @suppress Internal storage for the attached Stormify instance.
     *           Do not access directly — use [Stormify.attach] to set it.
     */
    var `!stormify`: Stormify?

    /**
     * Called by [Stormify.attach] after the reference is set.
     * Subclasses override this to refresh any state that depends on the attached instance
     * (e.g. invalidate cached table metadata or query results).
     */
    fun onAttached() {}
}
