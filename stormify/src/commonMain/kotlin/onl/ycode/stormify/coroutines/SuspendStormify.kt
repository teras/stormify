// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import onl.ycode.kdbc.Connection
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.asQuery
import onl.ycode.stormify.nextSavepointName
import onl.ycode.stormify.throwQuery
import kotlin.coroutines.coroutineContext

/**
 * Coroutine-aware wrapper around a [Stormify] instance.
 *
 * Obtained via [Stormify.suspending]. Provides a suspending `transaction { }` that
 * acquires a connection from a [SuspendConnectionPool], runs the block on the
 * platform IO dispatcher, wires coroutine cancellation to the underlying DB cancel
 * primitive, and correctly handles nesting via savepoints.
 *
 * The blocking [Stormify] instance passed to the constructor continues to work
 * independently — `SuspendStormify` is purely additive. A single process can use both
 * APIs side-by-side: some modules blocking, others suspending. They share no mutable
 * state; each `transaction { }` call (blocking or suspend) gets its own connection.
 *
 * ## Basic usage
 *
 * ```kotlin
 * val stormify = Stormify(KdbcDataSource("jdbc:sqlite:app.db"))
 * val async = stormify.suspending(PoolConfig(maxConnections = 8))
 *
 * async.transaction {
 *     val user = create(User(email = "a@b.c"))
 *     create(Profile(userId = user.id))
 * }
 * ```
 *
 * ## Nested transactions
 *
 * Calling [transaction] from within another `transaction { }` block on the same coroutine
 * lineage reuses the outer connection via a savepoint. The outer transaction's final
 * commit/rollback governs overall atomicity; the inner savepoint only bounds the rollback
 * scope if the inner block throws.
 *
 * ## Cancellation
 *
 * When a coroutine running a `transaction { }` is cancelled, the pool's cancel hook
 * dispatches [Connection.cancel] — which on Native maps directly to the driver-specific
 * async-cancel primitive (libpq `PQcancel`, `sqlite3_interrupt`, `mariadb_cancel`,
 * `dpiConn_breakExecution`). This causes the currently-blocking query to return with an
 * error, the transaction rolls back, and the connection is evicted from the pool (on the
 * assumption that its state may be inconsistent after a forced cancel). On JVM the
 * default `Connection.cancel` is a no-op — cancellation still unwinds the coroutine but
 * does not interrupt an in-flight query.
 *
 * ## launch { } inside transaction — strip semantics
 *
 * If you `launch { }` a child coroutine inside a `transaction { }`, the child inherits
 * [ConnectionElement] via standard coroutine context propagation. **Do not perform
 * parallel DB work from that child** — the parent's connection is already in use by the
 * parent block, and driver-level APIs forbid sharing a connection across threads. The
 * safe pattern is:
 *
 * ```kotlin
 * async.transaction {                 // outer tx, connection A
 *     launch {
 *         async.transaction {         // nested: opens savepoint on A, NOT parallel work
 *             ...
 *         }
 *     }
 * }
 * ```
 *
 * If you truly want parallel DB operations, start a **separate** top-level transaction
 * from outside any `transaction { }` block — each gets its own pool connection.
 */
public class SuspendStormify internal constructor(
    /** The blocking [Stormify] instance this suspend API wraps. Use it when you need the non-suspending API alongside. */
    public val stormify: Stormify,
    internal val pool: SuspendConnectionPool,
) {
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
     * Execute [block] inside a transaction. Commits on success, rolls back on any throwable.
     *
     * Failures surface as [onl.ycode.kdbc.SQLException] with the original throwable as
     * `cause` — the same contract as blocking [Stormify.transaction]. Coroutine
     * cancellation is exempt and always propagates unwrapped.
     *
     * If called from inside another `transaction { }` on the same [SuspendStormify]
     * (detected via the current [ConnectionElement] in the coroutine context),
     * reuses that connection via a savepoint rather than acquiring a new one.
     * While the block runs, convenience operations on the underlying [Stormify]
     * (CRUD, top-level extensions, lazy-loaders, `PagedList`, etc.) transparently
     * route through this transaction's connection via [ActiveTxRegistry].
     */
    @OptIn(InternalCoroutinesApi::class)
    public suspend fun <R> transaction(block: suspend () -> R): R {
        val existing = coroutineContext[ConnectionElement]
        if (existing != null) {
            require(existing.stormify === stormify) {
                "Nested transaction attached to a different Stormify instance " +
                        "(outer=${existing.stormify}, inner=$stormify)"
            }
            return nestedTransaction(existing.conn, block)
        }
        return topLevelTransaction(block)
    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalAtomicApi::class)
    private suspend fun <R> topLevelTransaction(block: suspend () -> R): R =
        pool.use { conn ->
            withTxDispatcher(ConnectionElement(conn, stormify)) {
                // Wire coroutine cancellation → DB cancel. `onCancelling = true` (from
                // InternalCoroutinesApi) fires the handler the moment the job enters the
                // cancelling state. The public `invokeOnCompletion(handler)` would fire
                // only after the coroutine has fully completed — too late to interrupt
                // a blocking C call.
                // Track whether the async cancel primitive fired. After OCIBreak
                // (Oracle) or similar driver-level cancel, the connection may be in a
                // state where subsequent synchronous calls (rollback, setAutoCommit)
                // block indefinitely — Oracle 11g requires OCIReset after OCIBreak,
                // which ODPI-C does not expose. Skipping rollback is safe because the
                // pool evicts the connection on failure anyway (releaseEntry with
                // success=false closes it).
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

                // Ambient propagation: on JVM/Android the [ConnectionElement] is
                // also a ThreadContextElement, so the runtime keeps the registry
                // in sync on every dispatcher hop automatically — [withSuspendAmbient]
                // is a no-op there. On Native the actual manually brackets the
                // body with a push/pop pair to cover the single-thread case.
                withSuspendAmbient(stormify, conn) {
                    try {
                        conn.setAutoCommit(false)
                        val result = block()
                        conn.commit()
                        result
                    } catch (e: Throwable) {
                        // Distinguish "user threw to roll back" from "connection broke":
                        // if rollback succeeds cleanly (and we weren't cancelled), the
                        // connection is still healthy — signal that to the pool via
                        // HealthyConnectionException so it isn't evicted on every throw.
                        // On any rollback failure, or when an async cancel fired, we let
                        // the original exception propagate so the pool evicts the entry.
                        var rollbackOk = false
                        if (cancelled.load() == 0) {
                            rollbackOk = runCatching { conn.rollback() }.isSuccess
                        } else {
                            // If cancelled, the driver decides what (if anything) is safe
                            // to run post-cancel — defaults to a no-op and relies on pool
                            // eviction.
                            runCatching { conn.cleanupAfterCancel() }
                        }
                        // Cancellation propagates unwrapped to preserve structured
                        // concurrency; everything else surfaces as SQLException (same
                        // contract as blocking Stormify.transaction), original as cause.
                        if (e is CancellationException) throw e
                        val wrapped = e.asQuery("Unable to execute transaction")
                        if (rollbackOk) throw HealthyConnectionException(wrapped)
                        throw wrapped
                    } finally {
                        if (cancelled.load() == 0) runCatching { conn.setAutoCommit(true) }
                        cancelHandle.dispose()
                    }
                }
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

/**
 * Wraps this [Stormify] instance with a coroutine-aware transaction API backed by
 * a [DefaultSuspendConnectionPool] configured with [config]. Safe to call multiple
 * times with different configs; each returned [SuspendStormify] is independent.
 */
public fun Stormify.suspending(config: PoolConfig = PoolConfig()): SuspendStormify =
    SuspendStormify(this, DefaultSuspendConnectionPool(dataSource, config))
