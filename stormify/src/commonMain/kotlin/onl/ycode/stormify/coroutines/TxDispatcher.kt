// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope

/**
 * Runs [block] on a dispatcher suitable for the platform's transaction model.
 *
 * On Android, the underlying `android.database.sqlite.SQLiteDatabase` keeps per-thread
 * transaction state via `ThreadLocal<SQLiteSession>`. If a coroutine calls
 * `beginTransaction()` on thread T1, suspends, and then calls `commit()`/`rollback()`
 * on thread T2, the Android session of T2 has no record of the transaction and the
 * SQLiteConnectionPool does not release the connection — subsequent operations
 * hang forever in `waitForConnection()`.
 *
 * To avoid this, the Android implementation pins the whole transaction body to a
 * single dedicated thread, so `begin`/`commit`/`rollback` all execute on the same
 * thread regardless of suspension points inside the block.
 *
 * JVM (JDBC) and Native (libpq / libmariadb / libsqlite3 / ODPI-C / FreeTDS) drivers
 * do not have thread affinity on connections, so their implementations dispatch on
 * the regular [ioDispatcher].
 */
internal expect suspend fun <R> withTxDispatcher(
    extraContext: CoroutineContext,
    block: suspend CoroutineScope.() -> R
): R
