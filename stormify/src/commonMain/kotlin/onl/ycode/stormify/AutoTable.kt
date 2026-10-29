// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmName
import kotlin.jvm.JvmSynthetic
import kotlin.jvm.Transient
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import onl.ycode.kdbc.SQLException

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
@OptIn(ExperimentalAtomicApi::class)
abstract class AutoTable : StormifyEntity() {

    // Fixed serialization version, so instances serialized by one release can be read
    // by the next as long as the serialized state is compatible.
    @Suppress("unused")
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
    /**
     * Tracks whether this entity's fields reflect the database row (i.e. no auto-load
     * is needed on the next [db]-delegated access). User-constructed entities start at
     * `true`. Library-constructed shadow references start at `false` until they are loaded.
     *
     * Serialized with the entity: a shadow reference that was never loaded stays
     * unloaded after deserialization and loads on first access, and a loaded or
     * user-built entity keeps its values. It is not transient on purpose: restored as
     * `false`, the first access would reload the row, discarding unsaved changes, and
     * would fail for an entity not yet in the database.
     *
     * Private and without accessors, so tools that work on bean properties (Jackson and
     * JAXB with their default settings) do not see it. Tools that read every
     * non-transient field do see it: Gson, JAXB with `XmlAccessType.FIELD`, Kryo's
     * field serializer. Exclude it there, or let it round-trip, which is harmless.
     */
    @Volatile private var _isHydrated = true
    // Private field, synthetic non-bean accessors (see StormifyEntity).
    @Transient
    @get:JvmSynthetic @get:JvmName("siblingGroup\$ref") @set:JvmSynthetic @set:JvmName("siblingGroup\$assign")
    internal var _siblingGroup: SiblingGroup? = null

    // The lock of this entity's load: created by the first thread that needs it, and let
    // go of once the entity is loaded, so a loaded entity carries no lock. A lock of the
    // entity's own rather than its monitor, which user code may hold, and which on the
    // JVM pins a virtual thread across the load's I/O. Serialized as null, or mid-load
    // as an unlocked lock; the holder is an atomic reference, so no platform code.
    private val _lock = AtomicReference<ReentrantLock?>(null)

    // The load state has three values. LOADED is `_isHydrated`, set only after a load
    // succeeds, so the lock-free fast path never sees half-filled fields. LOADING is one
    // of the two transient fields below; being transient, a serialized entity caught
    // mid-load comes back NOT_LOADED and loads again on first access.

    /** Loading as the entity that asked; read and written only under [loadLock]. */
    @Transient private var _loadingSelf = false

    /** Loading as a sibling in this group's batch; the group's lock is held for the load. */
    // Private field, synthetic non-bean accessors (see StormifyEntity).
    @Volatile @Transient
    @get:JvmSynthetic @get:JvmName("loadingGroup\$ref") @set:JvmSynthetic @set:JvmName("loadingGroup\$assign")
    internal var _loadingGroup: SiblingGroup? = null

    /**
     * True while a load of this entity is in progress (as the asking entity or a sibling).
     * A function not named `is…`/`get…`, so bean introspection does not take it for a property.
     */
    @JvmSynthetic internal fun loadInProgress(): Boolean = _loadingSelf || _loadingGroup != null

    /**
     * Fills this entity's fields from the database **only if it hasn't been filled yet**.
     * Thread-safe: concurrent callers wait for one load, on a lock of the entity's own,
     * never on the entity's monitor.
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
    @JvmSynthetic internal fun hydrateIfNeeded() {
        while (true) {
            if (_isHydrated) return
            val loadingGroup = _loadingGroup
            if (loadingGroup != null) {
                // Loaded right now as a sibling in a batch. The loading thread comes back
                // here through the setters it calls and must pass; any other thread waits
                // on the group for the batch to finish, then looks again.
                if (loadingGroup.isLoadingNow(this)) return
                continue
            }
            val ctr = _stormify ?: Stormify.current() ?: throw SQLException(
                "No Stormify instance to load ${this::class.simpleName}: attach the entity to one, " +
                        "or set Stormify.defaultInstance"
            )
            loadLock().withLock {
                // Loaded while this thread waited for the lock, or this thread's own load
                // coming back through the setters it calls.
                if (_isHydrated || _loadingSelf) return@withLock
                _loadingSelf = true
                try {
                    val group = _siblingGroup
                    if (group == null || !group.batchPopulate(this, ctr)) {
                        // Not in a batch, or another thread's batch took this entity while
                        // this thread waited: loaded there, or its row was missing, which
                        // a single load reports again ("No data found").
                        if (_isHydrated) return@withLock
                        if (!ctr.loadIfExists(this) && ctr.danglingReferences == DanglingReferences.THROW)
                            throw SQLException(
                                "No data found for ${this::class.simpleName} with id ${ctr.singleIdOrNull(this)}"
                            )
                    }
                    // Only now: a failed load leaves the entity to be retried.
                    _isHydrated = true
                    // Loaded for good: the lock is not needed again. A thread still waiting
                    // on it finds the entity loaded; a later one creates a lock and finds it too.
                    _lock.store(null)
                } finally {
                    _loadingSelf = false
                }
            }
            return
        }
    }

    /** The lock of this entity's load, created when first needed. Reentrant for the loading thread. */
    private fun loadLock(): ReentrantLock {
        while (true) {
            _lock.load()?.let { return it }
            val fresh = reentrantLock()
            if (_lock.compareAndSet(null, fresh)) return fresh
        }
    }

    /** Marks this entity as already hydrated, preventing any future auto-load by the [db] delegate. */
    fun markHydrated() {
        _isHydrated = true
    }

    /** Marks this entity as a shadow reference whose row is loaded on first access. */
    @JvmSynthetic internal fun markNotHydrated() {
        _isHydrated = false
    }
}
