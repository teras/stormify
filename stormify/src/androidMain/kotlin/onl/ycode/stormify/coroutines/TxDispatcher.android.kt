// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

// Pin the whole transaction body to a dedicated single thread: `SQLiteDatabase`
// stores its transaction state in a `ThreadLocal<SQLiteSession>`, so `begin`,
// `commit` and `rollback` MUST execute on the same thread. Coroutine suspensions
// inside the block would otherwise migrate to different workers and strand the
// session — the SQLiteConnectionPool then hangs forever in `waitForConnection()`.
@OptIn(DelicateCoroutinesApi::class)
internal actual suspend fun <R> withTxDispatcher(
    extraContext: CoroutineContext,
    block: suspend CoroutineScope.() -> R
): R {
    val pinned = newSingleThreadContext("stormify-android-tx")
    return try {
        withContext(pinned + extraContext, block)
    } finally {
        pinned.close()
    }
}
