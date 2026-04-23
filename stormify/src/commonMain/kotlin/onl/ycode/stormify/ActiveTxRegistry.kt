// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import onl.ycode.kdbc.Connection

/**
 * Thread-local stack of `(Stormify, Connection)` pairs describing the transactions
 * currently active on this thread. Used by `Stormify`'s internal CRUD/query
 * operations so that convenience entry points (top-level extensions, [CRUDTable],
 * lazy-loading delegates, `PagedList`, `PagedQuery`, …) transparently participate
 * in an enclosing `transaction { }` block instead of silently running on a fresh
 * auto-commit connection.
 *
 * **Explicit connection passing still wins.** Any internal overload that receives
 * a non-null [Connection] uses it verbatim and never consults the registry.
 * The lookup only fires when the caller passed `null` — which is exactly the
 * path the convenience APIs take.
 *
 * **Scope.** Blocking `Stormify.transaction { }` pushes on entry and pops on exit,
 * so all sync code running inside the lambda — on the same thread — sees the
 * ambient. For coroutine-based `SuspendStormify.transaction { }`, the JVM/Android
 * actual of [onl.ycode.stormify.coroutines.ConnectionElement] is a
 * `ThreadContextElement` that re-pushes the entry on every dispatcher hop, so
 * any blocking call issued from inside the suspend block observes the tx even
 * across thread migrations. Native pins the whole block to a dedicated thread
 * and brackets it with a single push/pop, which covers all cases except an
 * explicit `withContext(otherDispatcher)` that hops to a different worker
 * inside the block.
 *
 * **Multiple Stormify instances.** The stack stores the [Stormify] that opened
 * each transaction; lookup returns the topmost entry whose instance matches the
 * caller (`===`). A nested `A.transaction { B.create(...) }` therefore does
 * not hand `A`'s connection to `B`.
 */
internal expect object ActiveTxRegistry {
    /** Records [conn] as the currently-active transaction connection for [owner] on this thread. */
    fun push(owner: Stormify, conn: Connection)

    /**
     * Removes the most recent entry matching [owner] and [conn]. Safe to call
     * even if the entry is missing (e.g. if the tx is being torn down by a
     * finally block after a push failure) — a missing entry is a no-op.
     */
    fun pop(owner: Stormify, conn: Connection)

    /**
     * Returns the topmost active-tx connection opened by [owner] on this
     * thread, or `null` if this thread has no active transaction on [owner].
     */
    fun currentFor(owner: Stormify): Connection?
}
