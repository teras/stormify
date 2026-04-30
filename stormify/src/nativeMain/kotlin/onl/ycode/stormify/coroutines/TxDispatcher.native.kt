// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

// Pinning is provided by the connection-bound dispatcher attached to each
// [PoolEntry] (see [createEntryDispatcher]); the pool wraps the borrow in
// `withContext(entry.dispatcher) { … }` before this function runs, so the
// transaction body already executes on the connection's dedicated worker
// thread. This actual just splices [extraContext] (typically a
// [ConnectionElement]) into that dispatcher.
//
// Why pin: `ActiveTxRegistry` is backed by per-thread @ThreadLocal storage
// so that convenience APIs (CRUDTable, top-level extensions, lazy loaders,
// PagedList, …) called from inside the suspend block observe the current
// transaction's connection. On JVM/Android the `ConnectionElement` is also
// a `ThreadContextElement` and the coroutine runtime re-publishes the
// ambient on every dispatcher hop. That hook does not exist in the Native
// `kotlinx-coroutines-core` klib (1.10.x), so the body must stay pinned.
internal actual suspend fun <R> withTxDispatcher(
    extraContext: CoroutineContext,
    block: suspend CoroutineScope.() -> R
): R = withContext(extraContext, block)
