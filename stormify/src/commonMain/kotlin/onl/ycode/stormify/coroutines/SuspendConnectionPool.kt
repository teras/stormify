// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import onl.ycode.kdbc.Connection

/**
 * A suspend-aware connection pool.
 *
 * Designed to sit on top of a non-pooling [onl.ycode.kdbc.DataSource] (e.g. the bare
 * `NativeKdbcDataSource` on Native, or a plain `JdbcDataSource` wrapping a JDBC driver
 * without HikariCP). JVM users who already have HikariCP do not need this — they can
 * keep using the blocking `Stormify.transaction { }` API with a HikariCP-backed
 * DataSource.
 *
 * Concurrency contract:
 *  - `use { }` is the only supported way to borrow a connection. The block receives a
 *    connection that is valid for the lifetime of the block and MUST NOT be retained
 *    beyond it (no caching, no passing to other coroutines).
 *  - The pool itself is safe for concurrent `use { }` calls from multiple coroutines on
 *    multiple threads; internally it uses a fair FIFO semaphore.
 *  - A connection handed out by this pool is single-threaded-at-a-time. The `use { }` caller
 *    must not share the connection with parallel child coroutines.
 *
 * Error handling:
 *  - If `block` throws, the connection is evicted (closed, not returned to the idle
 *    queue). This prevents a corrupt connection from poisoning subsequent borrows.
 *    Non-connection errors (e.g. constraint violations) also trigger eviction by
 *    default — callers who want finer control can catch inside the block.
 *
 * Shutdown:
 *  - [close] triggers graceful shutdown: new acquires are rejected, in-flight `use { }`
 *    blocks are given [PoolConfig.shutdownTimeout] to complete, then remaining connections
 *    are forcibly closed.
 *  - After [close] returns, any further `use { }` call throws [PoolClosedException].
 */
internal interface SuspendConnectionPool {
    /**
     * Borrow a connection for the duration of [block], then release it back to the pool.
     *
     * @throws PoolClosedException if the pool has been closed.
     * @throws PoolAcquireTimeoutException if the pool is saturated and no connection
     *   becomes available within [PoolConfig.acquireTimeout].
     * @throws Throwable whatever [block] throws — the connection is evicted in that case.
     */
    suspend fun <R> use(block: suspend (Connection) -> R): R

    /**
     * Returns a snapshot of the pool's counters. Cheap — atomic reads only, no locking.
     */
    val stats: PoolStats

    /**
     * Graceful shutdown. See contract on the interface doc.
     * Safe to call multiple times; only the first call has effect.
     */
    suspend fun close()
}

/** Thrown by [SuspendConnectionPool.use] when the pool has been [SuspendConnectionPool.close]d. */
class PoolClosedException(message: String = "Connection pool is closed") : IllegalStateException(message)

/** Thrown by [SuspendConnectionPool.use] when [PoolConfig.acquireTimeout] elapses. */
class PoolAcquireTimeoutException(message: String) : RuntimeException(message)
