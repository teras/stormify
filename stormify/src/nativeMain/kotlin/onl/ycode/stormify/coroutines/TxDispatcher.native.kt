// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

// Pin the whole transaction body to a dedicated single thread.
//
// Why: `ActiveTxRegistry` is backed by per-thread @ThreadLocal storage so that
// convenience APIs (CRUDTable, top-level extensions, lazy loaders, PagedList,
// PagedQuery, …) called from inside the suspend block observe the current
// transaction's connection. On JVM/Android the `ConnectionElement` is also a
// `ThreadContextElement`, so the coroutine runtime re-publishes the ambient on
// every dispatcher hop automatically. That hook does not exist in the Native
// `kotlinx-coroutines-core` klib (1.10.x), so if the transaction body migrates
// to a different worker thread mid-flight — which is the default on Native
// because `Dispatchers.Default` is a multi-worker scheduler and any `delay`,
// `withContext`, or suspending library call can land the continuation on a
// different worker — blocking calls on the new thread would miss the ambient
// and silently auto-commit on a fresh connection.
//
// Pinning avoids the problem by guaranteeing the whole block runs on a single
// thread. Trade-off: we pay for one dedicated thread per transaction; DB work
// is inherently serialized on a single connection anyway, so no parallelism is
// lost. For non-DB suspending work interleaved with DB calls, the pin limits
// that work to one core inside the tx — tolerable since transactions are
// expected to be short-lived.
@OptIn(DelicateCoroutinesApi::class)
internal actual suspend fun <R> withTxDispatcher(
    extraContext: CoroutineContext,
    block: suspend CoroutineScope.() -> R
): R {
    val pinned = newSingleThreadContext("stormify-native-tx")
    return try {
        withContext(pinned + extraContext, block)
    } finally {
        pinned.close()
    }
}
