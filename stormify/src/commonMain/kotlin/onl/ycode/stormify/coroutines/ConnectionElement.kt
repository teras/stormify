// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import onl.ycode.kdbc.Connection
import onl.ycode.stormify.Stormify
import kotlin.coroutines.CoroutineContext

/**
 * CoroutineContext element that marks a coroutine as executing inside an active
 * [SuspendStormify] transaction, carrying the connection and its owning Stormify
 * instance.
 *
 * Two responsibilities:
 *
 *  1. **Nested-transaction detection**. When `suspending.transaction { }` is called
 *     from inside another such block on the same coroutine lineage, the inner call
 *     finds this element and opens a savepoint on the same connection instead of
 *     acquiring a new one. A mismatched [stormify] instance is caught with a clear
 *     error.
 *
 *  2. **Ambient propagation across dispatcher hops** (JVM / Android). Sync
 *     convenience calls (`stormify.create(x)`, top-level extensions, CRUDTable,
 *     lazy delegates, `PagedList`, …) rely on the thread-local
 *     [onl.ycode.stormify.ActiveTxRegistry] to locate the active transaction.
 *     A coroutine that suspends on thread A and resumes on thread B would lose that
 *     thread-local — the JVM/Android actual implements `ThreadContextElement` so
 *     the coroutine runtime re-pushes the registry entry on every dispatcher hop,
 *     keeping every blocking call inside the block consistent.
 *
 *     On Kotlin/Native the coroutine runtime's `ThreadContextElement` is not
 *     multiplatform as of 1.10.x; the native actual is a plain marker element.
 *     In practice Native coroutines rarely migrate threads inside a single
 *     `transaction { }` block, but a sync call after `withContext(Dispatchers.Default)`
 *     may not observe the ambient on non-JVM targets. Prefer to do DB work
 *     through the same coroutine scope without switching dispatchers, or call
 *     through the surrounding `SuspendStormify` API.
 *
 * Propagation follows standard `CoroutineContext.Element` rules — child coroutines
 * started with `launch { }` or `async { }` inside the block inherit this element.
 * Drivers forbid parallel use of a single connection, so do not issue concurrent
 * DB work from those children. See [SuspendStormify.transaction] for the pattern.
 */
internal expect class ConnectionElement(conn: Connection, stormify: Stormify) : CoroutineContext.Element {
    internal val conn: Connection
    internal val stormify: Stormify
    override val key: CoroutineContext.Key<*>

    companion object Key : CoroutineContext.Key<ConnectionElement>
}
