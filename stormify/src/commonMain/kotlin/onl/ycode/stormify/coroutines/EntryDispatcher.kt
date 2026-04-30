// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package onl.ycode.stormify.coroutines

import kotlinx.coroutines.CloseableCoroutineDispatcher

/**
 * Creates a dedicated single-thread dispatcher to bind to a pooled connection,
 * or null when no per-connection pinning is required for this platform.
 *
 * Pinning rationale per platform:
 *  - **JVM (JDBC)**: drivers are thread-safe per connection across threads, and the
 *    [Stormify] blocking convenience APIs do not require thread affinity, so the
 *    transaction body runs on `Dispatchers.IO` and no dedicated thread is needed.
 *    Returns null.
 *  - **Android**: `SQLiteDatabase` keeps transaction state in a `ThreadLocal<SQLiteSession>`,
 *    so `begin`/`commit`/`rollback` must execute on the same thread for the lifetime of
 *    the transaction. Returns a single-thread dispatcher bound to the connection.
 *  - **Native**: `kotlinx-coroutines-core` 1.10 does not export `ThreadContextElement`
 *    to Native klibs, so [ActiveTxRegistry] thread-locals are not auto-republished on
 *    dispatcher hops. The transaction body must therefore stay pinned to a single
 *    thread for the duration of the borrow. Returns a single-thread dispatcher.
 *
 * The dispatcher is created once when the pool entry is created and reused for every
 * subsequent borrow of the same connection, then closed when the entry is evicted.
 * This replaces the earlier per-transaction `newSingleThreadContext` pattern, which
 * paid `pthread_create` + `pthread_join` on each `transaction { }` call.
 */
internal expect fun createEntryDispatcher(name: String): CloseableCoroutineDispatcher?
