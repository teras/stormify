// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import onl.ycode.kdbc.Connection
import onl.ycode.stormify.Stormify
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * CoroutineContext element that marks a coroutine as executing inside an active
 * [SuspendStormify] transaction, carrying the connection and its owning Stormify
 * instance.
 *
 * This enables two features:
 *
 *  - **Nested transaction detection**: when `stormify.suspending(pool).transaction { }`
 *    is called from inside another such block on the same coroutine lineage, the inner
 *    call sees the existing element and opens a savepoint on the same connection instead
 *    of acquiring a new one from the pool. The outer transaction's commit/rollback still
 *    governs the overall atomicity.
 *
 *  - **Guardrail against cross-stormify nesting**: if a user (accidentally) tries to nest
 *    a transaction from a *different* Stormify instance inside the current one, the
 *    mismatch is detected via [stormify] identity comparison and thrown.
 *
 * Propagation semantics follow standard `CoroutineContext.Element` rules: child
 * coroutines started with `launch { }` or `async { }` inside a transaction block will
 * inherit this element by default. That inheritance is a double-edged sword — see the
 * launch-in-transaction notes on [SuspendStormify.transaction].
 */
public class ConnectionElement internal constructor(
    internal val conn: Connection,
    internal val stormify: Stormify,
) : AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<ConnectionElement>
}
