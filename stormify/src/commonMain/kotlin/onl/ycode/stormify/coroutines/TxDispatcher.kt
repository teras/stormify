// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope

/**
 * Splices [extraContext] (typically a [ConnectionElement]) into the coroutine
 * context for the duration of [block].
 *
 * Per-platform behavior:
 *  - **JVM**: dispatches on [ioDispatcher] so blocking JDBC calls do not stall the
 *    caller's dispatcher. No thread pinning is required since JDBC connections are
 *    not thread-affine and ConnectionElement is a ThreadContextElement that the
 *    coroutine runtime auto-republishes on dispatcher hops.
 *  - **Android & Native**: pinning is supplied upstream by the connection-bound
 *    dispatcher attached to each pool entry (see [createEntryDispatcher]); the pool
 *    wraps the borrow in `withContext(entry.dispatcher) { … }` before this function
 *    runs. The actuals therefore just install [extraContext] without changing
 *    dispatcher, keeping the body on the connection's dedicated worker thread.
 */
internal expect suspend fun <R> withTxDispatcher(
    extraContext: CoroutineContext,
    block: suspend CoroutineScope.() -> R
): R
