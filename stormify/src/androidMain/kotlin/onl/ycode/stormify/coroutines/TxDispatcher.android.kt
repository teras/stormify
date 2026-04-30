// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

// Pinning is provided by the connection-bound dispatcher attached to each
// [PoolEntry] (see [createEntryDispatcher]); the pool wraps the borrow in
// `withContext(entry.dispatcher) { … }` before this function runs, so
// `begin`/`commit`/`rollback` and every blocking call inside the
// transaction execute on the connection's dedicated thread, satisfying
// `SQLiteDatabase`'s ThreadLocal<SQLiteSession> contract.
internal actual suspend fun <R> withTxDispatcher(
    extraContext: CoroutineContext,
    block: suspend CoroutineScope.() -> R
): R = withContext(extraContext, block)
