// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlin.jvm.Transient
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Base class for entities that support auto-hydration from the database.
 *
 * Two ways an [AutoTable] comes to life:
 *
 * - **User-constructed** (`Entity()` in user code, possibly via `apply { id = … }`):
 *   the entity is considered already in a usable state. Reading or writing a
 *   property delegated via [db] never triggers a DB round-trip. The user is in
 *   control: if they want DB values, they call
 *   [Stormify.refresh][onl.ycode.stormify.Stormify.refresh] (or use
 *   [findById][onl.ycode.stormify.findById]).
 * - **Library-constructed shadow reference** (the FK stub the library creates when
 *   it loads a parent that points to this row): on the first read or write of any
 *   [db]-delegated property, the row is auto-loaded by the library, filling every
 *   field; then the original operation proceeds.
 *
 * When multiple shadows share the same [SiblingGroup][onl.ycode.stormify.SiblingGroup],
 * they are batch-loaded in a single query.
 *
 * Subclasses that hand-write getters/setters (typically Java code) can call [hydrate]
 * to implement the same at-most-once lazy-load pattern that the Kotlin [db] delegate
 * applies automatically.
 */
abstract class AutoTable : StormifyEntity() {
    private val lock = SynchronizedObject()

    /**
     * Tracks whether this entity's fields reflect the database row (i.e. no auto-load
     * is needed on the next [db]-delegated access). User-constructed entities start at
     * `true`. Library-constructed shadow references start at `false` until they are loaded.
     */
    @Transient internal val _isHydrated = atomic(true)
    @Transient internal var _siblingGroup: SiblingGroup? = null

    /**
     * Fills this entity's fields from the database **only if it hasn't been filled yet**.
     * Thread-safe.
     *
     * Subclasses that hand-write their getters/setters call this to implement the
     * lazy-load pattern, just like the Kotlin [db] delegate does automatically:
     *
     * ```java
     * public String getTitle() { hydrate(); return title; }
     * public void setTitle(String title) { hydrate(); this.title = title; }
     * ```
     */
    protected fun hydrate() = hydrateIfNeeded()

    /** Internal entry point used by the [db] delegate; delegates to [hydrate]. */
    internal fun hydrateIfNeeded() {
        if (_isHydrated.value) return
        val ctr = _stormify ?: Stormify.defaultInstance ?: return
        synchronized(lock) {
            if (_isHydrated.value) return
            // Set before the SELECT so that reflection setters invoked during hydration
            // don't re-trigger a load through the db delegate.
            _isHydrated.value = true
            val group = _siblingGroup
            if (group != null)
                group.batchPopulate(this)
            else
                ctr.refresh(this)
        }
    }

    /** Marks this entity as already hydrated, preventing any future auto-load by the [db] delegate. */
    fun markHydrated() {
        _isHydrated.value = true
    }
}
