// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Production-grade [SuspendConnectionPool] implementation.
 *
 * Design notes:
 *
 *  - **Acquisition fairness**: a single [Semaphore] sized to `maxConnections` is the only
 *    admission gate. Waiters are suspended, not thread-blocked, and woken in FIFO order.
 *    No polling, no busy-waiting.
 *  - **Validation lives outside the idle-queue lock**: the mutex is held only long enough
 *    to pop a candidate from the idle deque. A potentially slow `validationQuery` runs
 *    without blocking other borrowers.
 *  - **Background cleanup**: a dedicated coroutine sweeps the idle deque every
 *    [PoolConfig.cleanupInterval], retiring connections that have been idle too long or
 *    exceeded `maxLifetime`. The sweep respects [PoolConfig.minConnections] — it never
 *    drops below that floor.
 *  - **Error eviction**: any throwable inside `use { }` causes the connection to be
 *    closed, not returned to the pool. A broken connection cannot poison subsequent
 *    borrows.
 *  - **Graceful shutdown**: [close] sets a closed flag, waits up to
 *    [PoolConfig.shutdownTimeout] for in-flight borrowers to return, then force-closes
 *    idle connections and cancels the background coroutine.
 *  - **Atomic stats**: all counters are `atomicfu` and read lock-free. [stats] is cheap
 *    enough to poll from metrics endpoints.
 *
 * Invariants maintained internally (for reviewers):
 *
 *  1. `(in-use + being-created) <= maxConnections` at all times, enforced by the
 *     semaphore: every `createEntry` call holds a permit, every `releaseEntry` releases one.
 *  2. `totalConnections == idle.size + inUseCount` approximately — the counters are
 *     incremented/decremented around create/release so they may be momentarily off while a
 *     borrow transitions, but any quiescent snapshot is consistent.
 *  3. An entry in the `idle` deque always has `valid == true`; evicted entries are removed
 *     from the deque before `closeEntry` runs.
 */
public class DefaultSuspendConnectionPool(
    private val dataSource: DataSource,
    private val config: PoolConfig = PoolConfig(),
) : SuspendConnectionPool {

    private val permits = Semaphore(permits = config.maxConnections, acquiredPermits = 0)

    /** Held only for O(1) pop/push on [idle]; validation and close run outside. */
    private val idleMutex = Mutex()

    /** LIFO order gives most-recently-used-first reuse, for driver-side cache warmth. */
    private val idle: ArrayDeque<PoolEntry> = ArrayDeque()

    private val closed = atomic(false)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + CoroutineName("stormify-pool"))
    private val timeSource = TimeSource.Monotonic

    private val totalConnections = atomic(0)
    private val inUseCount = atomic(0)
    private val acquireCount = atomic(0L)
    private val waitedAcquireCount = atomic(0L)
    private val evictedCount = atomic(0L)
    private val retiredCount = atomic(0L)

    init {
        if (config.minConnections > 0) {
            scope.launch { prewarm() }
        }
        scope.launch { cleanupLoop() }
    }

    // --- public API ----------------------------------------------------------

    override val stats: PoolStats
        get() {
            val total = totalConnections.value
            val inUse = inUseCount.value
            return PoolStats(
                total = total,
                inUse = inUse,
                idle = (total - inUse).coerceAtLeast(0),
                acquireCount = acquireCount.value,
                waitedAcquireCount = waitedAcquireCount.value,
                evictedCount = evictedCount.value,
                retiredCount = retiredCount.value,
            )
        }

    override suspend fun <R> use(block: suspend (Connection) -> R): R {
        if (closed.value) throw PoolClosedException()
        val entry = acquireEntry()
        var success = false
        try {
            val result = block(entry.connection)
            success = true
            return result
        } finally {
            releaseEntry(entry, success)
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return

        // Wait for in-flight borrowers with a bounded grace period.
        val waitStart = timeSource.markNow()
        while (inUseCount.value > 0 && waitStart.elapsedNow() < config.shutdownTimeout) {
            delay(50.milliseconds)
        }

        // Drain the idle deque and close everything we still own.
        val toClose = idleMutex.withLock {
            val snapshot = idle.toList()
            idle.clear()
            snapshot
        }
        toClose.forEach { closeEntry(it) }

        // Stop the background cleanup loop and any outstanding prewarm launch.
        scope.cancel()
    }

    // --- acquire / release ---------------------------------------------------

    private suspend fun acquireEntry(): PoolEntry {
        // Track "would have waited" purely for metrics. Observing availablePermits is
        // inherently racy, but this is a stat, not a correctness concern.
        val wouldWait = permits.availablePermits == 0

        val permitAcquired = withTimeoutOrNull(config.acquireTimeout) {
            permits.acquire()
            true
        } ?: throw PoolAcquireTimeoutException(
            "Timed out after ${config.acquireTimeout} waiting for a connection " +
                    "(maxConnections=${config.maxConnections}, inUse=${inUseCount.value})"
        )
        check(permitAcquired)

        if (wouldWait) waitedAcquireCount.incrementAndGet()

        // Hoisted so the catch block can close the entry if cancellation fires between
        // popping it from idle and handing it to the caller (otherwise it would be
        // orphaned: out of the deque, not closed, still counted in totalConnections).
        var candidate: PoolEntry? = null
        try {
            // Loop: validation may reject a candidate and force a retry.
            while (true) {
                if (closed.value) throw PoolClosedException()

                candidate = idleMutex.withLock {
                    if (idle.isNotEmpty()) idle.removeLast() else null
                }

                if (candidate == null) {
                    // No idle entries — create a new one while holding the permit.
                    val entry = createEntry()
                    candidate = entry  // track for cleanup in catch
                    inUseCount.incrementAndGet()
                    acquireCount.incrementAndGet()
                    candidate = null   // successful hand-off, don't let catch close it
                    return entry
                }

                // Validation runs OUTSIDE the idle mutex so slow queries don't block
                // other borrowers.
                if (isExpired(candidate)) {
                    closeEntry(candidate)
                    candidate = null
                    retiredCount.incrementAndGet()
                    continue
                }
                if (shouldValidate(candidate) && !validateEntry(candidate)) {
                    closeEntry(candidate)
                    candidate = null
                    evictedCount.incrementAndGet()
                    continue
                }

                inUseCount.incrementAndGet()
                acquireCount.incrementAndGet()
                val entry = candidate
                candidate = null  // successful hand-off
                return entry
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable — while(true) above returns or throws")
        } catch (e: Throwable) {
            // Orphaned candidate cleanup — a non-null candidate here means we popped
            // from idle but did not successfully hand it to the caller.
            candidate?.let { closeEntry(it) }
            permits.release()
            throw e
        }
    }

    private suspend fun releaseEntry(entry: PoolEntry, success: Boolean) {
        inUseCount.decrementAndGet()
        try {
            if (!success || closed.value || !entry.valid) {
                closeEntry(entry)
                if (!success) evictedCount.incrementAndGet()
            } else {
                entry.lastUsed = timeSource.markNow()
                idleMutex.withLock { idle.addLast(entry) }
            }
        } finally {
            permits.release()
        }
    }

    // --- entry lifecycle -----------------------------------------------------

    private suspend fun createEntry(): PoolEntry {
        val conn = withContext(ioDispatcher) { dataSource.getConnection() }
        totalConnections.incrementAndGet()
        val now = timeSource.markNow()
        return PoolEntry(conn, createdAt = now, lastUsed = now)
    }

    private fun closeEntry(entry: PoolEntry) {
        if (!entry.valid) return
        entry.valid = false
        totalConnections.decrementAndGet()
        try {
            entry.connection.close()
        } catch (e: Throwable) {
            // Disposal errors are swallowed intentionally — the connection is already
            // considered dead, and reporting close-failures during cleanup causes more
            // confusion than it prevents.
        }
    }

    // --- validation & expiry -------------------------------------------------

    private fun shouldValidate(entry: PoolEntry): Boolean {
        if (config.validationQuery == null) return false
        return entry.lastUsed.elapsedNow() > config.validateAfterIdle
    }

    private suspend fun validateEntry(entry: PoolEntry): Boolean {
        val query = config.validationQuery ?: return true
        return withContext(ioDispatcher) {
            try {
                entry.connection.initStatement(query, false, null).use { stmt ->
                    stmt.executeQuery().use { rs -> rs.next() }
                }
                true
            } catch (e: CancellationException) {
                // Never swallow cancellation — the caller needs to observe it and unwind.
                throw e
            } catch (e: Throwable) {
                false
            }
        }
    }

    private fun isExpired(entry: PoolEntry): Boolean {
        if (config.maxLifetime == Duration.INFINITE) return false
        return entry.createdAt.elapsedNow() > config.maxLifetime
    }

    // --- background maintenance ----------------------------------------------

    private suspend fun prewarm() {
        repeat(config.minConnections) {
            if (closed.value || !scope.isActive) return
            try {
                permits.acquire()
                try {
                    val entry = createEntry()
                    idleMutex.withLock { idle.addLast(entry) }
                } finally {
                    permits.release()
                }
            } catch (e: CancellationException) {
                // Scope cancellation (e.g. from close()) is expected during shutdown —
                // let it propagate so the coroutine terminates promptly.
                throw e
            } catch (e: Throwable) {
                // Pre-warm is best-effort for other errors. On failure, the pool falls
                // back to on-demand creation; subsequent acquires will retry.
                return
            }
        }
    }

    private suspend fun cleanupLoop() {
        while (scope.isActive && !closed.value) {
            delay(config.cleanupInterval)
            if (!closed.value) runCleanup()
        }
    }

    private suspend fun runCleanup() {
        // Collect expired/idle-too-long entries while respecting minConnections.
        val toDispose = mutableListOf<Pair<PoolEntry, Boolean /* expired */>>()
        idleMutex.withLock {
            val iter = idle.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val expired = isExpired(entry)
                val tooIdle = entry.lastUsed.elapsedNow() > config.idleTimeout
                if (!expired && !tooIdle) continue

                val remainingIfRemoved = totalConnections.value - toDispose.size - 1
                if (remainingIfRemoved < config.minConnections) continue

                iter.remove()
                toDispose.add(entry to expired)
            }
        }

        toDispose.forEach { (entry, wasExpired) ->
            closeEntry(entry)
            if (wasExpired) retiredCount.incrementAndGet() else evictedCount.incrementAndGet()
        }
    }
}

/**
 * Internal bookkeeping entry for a pooled connection.
 *
 * [valid] does not need a volatile/atomic marker because every read/write happens under
 * [DefaultSuspendConnectionPool.idleMutex], OR after the entry has already been removed
 * from the idle deque (at which point only a single caller holds a reference to it).
 */
internal class PoolEntry(
    val connection: Connection,
    val createdAt: TimeSource.Monotonic.ValueTimeMark,
    var lastUsed: TimeSource.Monotonic.ValueTimeMark,
) {
    var valid: Boolean = true
}
