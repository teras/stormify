// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import onl.ycode.kdbc.Connection
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.asQuery
import onl.ycode.stormify.nextSavepointName
import onl.ycode.stormify.throwQuery
import kotlin.coroutines.coroutineContext

/**
 * Coroutine-aware wrapper around a [Stormify] instance, backed by a connection pool.
 *
 * **One contract:** inside a scope, every database operation uses the scope's
 * connection. The blocking [Stormify] API (CRUD, top-level extensions, lazy loaders,
 * `PagedList`, `PagedQuery`, …) is called unchanged inside the scope and joins the
 * borrowed connection transparently.
 *
 * Two scopes are available:
 *
 *  - [withConnection] — borrows a pooled connection for the duration of the block.
 *    Auto-commit stays on; no transaction is started. Use it for reads and for any
 *    work that does not need atomicity. Exceptions propagate **unchanged** (no
 *    `SQLException` wrapping) and do not destroy the borrowed connection.
 *  - [transaction] — the same borrow, plus BEGIN/COMMIT around the block (rollback
 *    on any throwable). Use it only where atomicity is required.
 *
 * ```kotlin
 * stormify.suspending.withConnection {
 *     val suppliers = findAll<Supplier>("WHERE active = ?", true)
 * }
 *
 * stormify.suspending.transaction {
 *     order.update()
 *     Stock.receive(order.warehouse!!, product, qty)
 * }
 * ```
 *
 * **Nesting.** Scopes compose on the same coroutine lineage:
 *
 * | inner ↓ / outer → | `withConnection` | `transaction` |
 * |---|---|---|
 * | `withConnection` | reuse the connection | reuse the connection, autoCommit untouched |
 * | `transaction` | full BEGIN/COMMIT on the ambient connection | savepoint on the ambient connection |
 *
 * **Pooling is suspend-only, by design.** Each pooled connection is pinned to a
 * dedicated worker thread (Native drivers and Android's `SQLiteSession` forbid
 * cross-thread connection use), so a blocking `getConnection()` could never safely
 * borrow from this pool. If you want pooling, write suspend code. There will be no
 * `PooledDataSource` for the blocking API.
 *
 * **Closing.** [close] shuts the pool down gracefully. On Native every pooled
 * connection owns a thread — skipping [close] leaks threads, not just connections.
 *
 * **Double-pooling warning.** If your [onl.ycode.kdbc.DataSource] already pools
 * (e.g. HikariCP on JVM), this pool holds up to [PoolConfig.maxConnections] of its
 * connections permanently. Keep [PoolConfig.maxConnections] well below the outer
 * pool's size so other consumers (migrations, health checks, other frameworks)
 * are not starved, and raise or disable the outer pool's leak-detection threshold,
 * which will otherwise flag the long-lived borrows.
 *
 * ## Internals
 *
 * Nesting is detected via a [ConnectionElement] in the coroutine context. The
 * ambient connection is published to the blocking API through
 * `ActiveTxRegistry` — on JVM/Android re-published by the coroutine runtime on
 * every dispatcher hop, on Native pinned to the connection's worker thread for the
 * whole block. Coroutine cancellation is wired to the driver's async-cancel
 * primitive (`sqlite3_interrupt`, `PQcancel`, …) and always evicts the connection.
 *
 * The blocking [Stormify] instance passed to the constructor continues to work
 * independently; the two APIs share no mutable state.
 */
public class SuspendStormify internal constructor(
    /** The blocking [Stormify] instance this suspend API wraps. */
    public val stormify: Stormify,
    internal val pool: SuspendConnectionPool,
) {
    /**
     * Creates a [SuspendStormify] with its own, independent connection pool.
     *
     * This is the escape hatch for code that needs a pool separate from the one
     * behind [Stormify.suspending] (e.g. an isolated reporting workload). Most
     * applications should use the `stormify.suspending` property instead, so the
     * whole process shares a single pool.
     */
    public constructor(
        stormify: Stormify,
        config: PoolConfig = PoolConfig(),
    ) : this(stormify, DefaultSuspendConnectionPool(stormify.dataSource, config))

    /**
     * Gracefully shuts down the underlying connection pool.
     * See [SuspendConnectionPool.close] for semantics.
     */
    public suspend fun close() { pool.close() }

    /**
     * Returns a snapshot of the pool's counters.
     */
    public val stats: PoolStats get() = pool.stats

    /**
     * Borrows a pooled connection for the duration of [block], without starting a
     * transaction. The connection stays in auto-commit mode; every statement inside
     * commits independently.
     *
     * If called inside another `withConnection { }` or `transaction { }` on the same
     * [SuspendStormify], reuses that scope's connection (auto-commit is never touched).
     *
     * **Exception contract:** whatever [block] throws propagates to the caller
     * unchanged — unlike [transaction], no `SQLException` wrapping happens on this
     * path. Application-level exceptions (validation, not-found, constraint
     * violations in auto-commit) do not harm the borrowed connection; it is
     * returned to the pool and reused. Only coroutine cancellation evicts it.
     */
    @OptIn(ExperimentalAtomicApi::class)
    public suspend fun <R> withConnection(block: suspend () -> R): R {
        val existing = coroutineContext[ConnectionElement]
        if (existing != null) {
            require(existing.stormify === stormify) {
                "Nested withConnection attached to a different Stormify instance " +
                        "(outer=${existing.stormify}, inner=$stormify)"
            }
            return block()
        }
        return borrowConnection(inTransaction = false) { _, cancelled ->
            try {
                block()
            } catch (e: Throwable) {
                // Cancellation — or a cancel that raced with an application throw —
                // leaves the connection in an unknown state: propagate unwrapped so
                // the pool evicts it.
                if (e is CancellationException || cancelled.load() != 0) throw e
                // Everything else leaves an auto-commit connection healthy. The
                // wrapper tells the pool to keep it and rethrow the original.
                throw HealthyConnectionException(e)
            }
        }
    }

    /**
     * Execute [block] inside a transaction. Commits on success, rolls back on any throwable.
     *
     * Failures surface as [onl.ycode.kdbc.SQLException] with the original throwable as
     * `cause` — the same contract as blocking [Stormify.transaction]. Coroutine
     * cancellation is exempt and always propagates unwrapped.
     *
     * If called from inside another `transaction { }` on the same [SuspendStormify],
     * opens a savepoint on the ambient connection. If called from inside a bare
     * `withConnection { }`, runs a full BEGIN/COMMIT cycle on that connection.
     * While the block runs, convenience operations on the underlying [Stormify]
     * transparently route through this transaction's connection.
     */
    @OptIn(ExperimentalAtomicApi::class)
    public suspend fun <R> transaction(block: suspend () -> R): R {
        val existing = coroutineContext[ConnectionElement]
        if (existing != null) {
            require(existing.stormify === stormify) {
                "Nested transaction attached to a different Stormify instance " +
                        "(outer=${existing.stormify}, inner=$stormify)"
            }
            return if (existing.inTransaction) {
                nestedTransaction(existing.conn, block)
            } else {
                runTxBody(existing.conn, block, cancelled = null)
            }
        }
        return borrowConnection(inTransaction = true) { conn, cancelled ->
            runTxBody(conn, block, cancelled)
        }
    }

    /**
     * The shared borrow machinery for both scopes: acquire a pooled connection, pin
     * the block to the connection's dispatcher, publish the ambient connection, and
     * wire coroutine cancellation to the driver cancel primitive.
     *
     * `onCancelling = true` (from [InternalCoroutinesApi]) fires the handler the
     * moment the job enters the cancelling state; the public `invokeOnCompletion`
     * would fire only after the coroutine has fully completed — too late to
     * interrupt a blocking C call.
     */
    @OptIn(InternalCoroutinesApi::class, ExperimentalAtomicApi::class)
    private suspend fun <R> borrowConnection(
        inTransaction: Boolean,
        body: suspend (Connection, AtomicInt) -> R,
    ): R =
        pool.use { conn ->
            withTxDispatcher(ConnectionElement(conn, stormify, inTransaction)) {
                val cancelled = AtomicInt(0)
                val job = coroutineContext[Job]!!
                val cancelHandle = job.invokeOnCompletion(
                    onCancelling = true,
                    invokeImmediately = true,
                ) { throwable ->
                    if (throwable != null) {
                        cancelled.store(1)
                        runCatching { conn.cancel() }
                    }
                }
                try {
                    withSuspendAmbient(stormify, conn) { body(conn, cancelled) }
                } finally {
                    cancelHandle.dispose()
                }
            }
        }

    /**
     * BEGIN/COMMIT around [block] on an already-borrowed connection. When [cancelled]
     * is null (running inside a `withConnection { }` scope), cancellation bookkeeping
     * belongs to the enclosing borrow — a botched rollback there is harmless because
     * the enclosing scope decides the connection's fate.
     *
     * Rollback policy: after a driver-level async cancel, the connection may be in a
     * state where subsequent synchronous calls block indefinitely (Oracle 11g requires
     * OCIReset after OCIBreak, which ODPI-C does not expose), so rollback is skipped
     * and the connection is left for the pool to evict. A clean rollback marks the
     * connection healthy via [HealthyConnectionException] so the pool keeps it.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun <R> runTxBody(conn: Connection, block: suspend () -> R, cancelled: AtomicInt?): R {
        try {
            conn.setAutoCommit(false)
            val result = block()
            conn.commit()
            return result
        } catch (e: Throwable) {
            var rollbackOk = false
            if (cancelled == null || cancelled.load() == 0) {
                rollbackOk = runCatching { conn.rollback() }.isSuccess
            } else {
                runCatching { conn.cleanupAfterCancel() }
            }
            // Cancellation propagates unwrapped to preserve structured concurrency;
            // everything else surfaces as SQLException (same contract as blocking
            // Stormify.transaction), original as cause.
            if (e is CancellationException) throw e
            val wrapped = e.asQuery("Unable to execute transaction")
            // HealthyConnectionException is a signal for the pool boundary — only
            // meaningful when this transaction owns the borrow (cancelled != null).
            // Nested inside withConnection { } there is no pool boundary ahead, so
            // the marker must not leak into user code; the enclosing withConnection
            // re-wraps for the pool on its own.
            if (rollbackOk && cancelled != null) throw HealthyConnectionException(wrapped)
            throw wrapped
        } finally {
            if (cancelled == null || cancelled.load() == 0) runCatching { conn.setAutoCommit(true) }
        }
    }

    private suspend fun <R> nestedTransaction(
        conn: Connection,
        block: suspend () -> R,
    ): R {
        val savepoint = try {
            conn.setSavepoint(nextSavepointName())
        } catch (e: Throwable) {
            e.throwQuery("Unable to open savepoint for nested transaction")
        }
        return try {
            val result = block()
            if (stormify.sqlDialect.supportsReleaseSavepoint) {
                runCatching { conn.releaseSavepoint(savepoint) }
            }
            result
        } catch (e: Throwable) {
            runCatching { conn.rollback(savepoint) }
            if (e is CancellationException) throw e
            e.throwQuery("Unable to execute nested transaction")
        }
    }
}
