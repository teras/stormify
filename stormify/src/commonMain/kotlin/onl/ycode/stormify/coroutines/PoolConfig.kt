// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning for the connection pool behind [SuspendStormify].
 *
 * **You normally don't need this.** The defaults are sensible for most
 * applications; pass a `PoolConfig` only to tune. Note that with
 * [maxConnections] at its default, a server that borrows one connection per
 * request can serve at most [maxConnections] requests concurrently — further
 * requests suspend up to [acquireTimeout] and then fail with
 * [PoolAcquireTimeoutException]. Size the pool to your expected concurrency.
 *
 * **Double-pooling warning:** if your `DataSource` already pools connections
 * (e.g. HikariCP on JVM), this pool holds up to [maxConnections] of its
 * connections permanently. Keep [maxConnections] well below the outer pool's
 * size so other consumers (migration tools, health checks, other frameworks)
 * are not starved, and raise or disable the outer pool's leak-detection
 * threshold — long-lived borrows are normal here, not leaks.
 *
 * All durations use `kotlin.time.Duration`. All validations in `init` are intentionally
 * strict — it is better to fail fast with a clear message than to produce a pool with
 * pathological behaviour at runtime.
 */
public data class PoolConfig(
    /**
     * Minimum number of connections kept open in the pool at all times, even when idle.
     * The pool pre-populates to this size on first use. Set to 0 to disable warm-up.
     */
    val minConnections: Int = 2,

    /**
     * Hard upper bound on concurrent connections. A caller that tries to acquire when the
     * pool is saturated will suspend (not block a thread) until another caller releases or
     * [acquireTimeout] elapses.
     */
    val maxConnections: Int = 10,

    /**
     * Maximum time to wait for a free connection when the pool is saturated. On timeout,
     * [SuspendConnectionPool.use] throws [PoolAcquireTimeoutException].
     */
    val acquireTimeout: Duration = 30.seconds,

    /**
     * A connection that has been idle longer than this is eligible for eviction by the
     * background cleanup coroutine. Connections down to [minConnections] are always
     * preserved, regardless of idle time.
     */
    val idleTimeout: Duration = 5.minutes,

    /**
     * Absolute lifetime of a connection from creation to forced retirement, regardless of
     * activity. Prevents long-lived sessions from accumulating server-side state (prepared
     * statement cache growth, session-level temp tables, etc.). Set to [Duration.INFINITE]
     * to disable max-lifetime retirement.
     */
    val maxLifetime: Duration = 30.minutes,

    /**
     * How often the background cleanup coroutine runs idle/lifetime sweeps. Smaller values
     * release dead connections sooner at the cost of more wake-ups.
     */
    val cleanupInterval: Duration = 30.seconds,

    /**
     * Optional SQL that validates a connection before handing it to a caller. If null, no
     * validation is performed — the pool relies on [SuspendConnectionPool.use] to evict
     * connections that raise errors during use. Typical values: `"SELECT 1"` (most databases),
     * `"SELECT 1 FROM DUAL"` (Oracle).
     *
     * Validation only runs when the candidate connection has been idle longer than
     * [validateAfterIdle] — connections released within that window are assumed alive.
     */
    val validationQuery: String? = null,

    /**
     * Skip validation for connections released within this window. A just-returned connection
     * is very likely still alive; validating every borrow wastes round-trips.
     */
    val validateAfterIdle: Duration = 10.seconds,

    /**
     * Graceful-shutdown grace period. When [SuspendConnectionPool.close] is invoked the pool
     * stops accepting new acquires and waits up to this long for in-flight `use { }` blocks
     * to return their connections before forcibly closing what remains.
     */
    val shutdownTimeout: Duration = 30.seconds,
) {
    init {
        require(minConnections >= 0) { "minConnections must be >= 0 (was $minConnections)" }
        require(maxConnections >= 1) { "maxConnections must be >= 1 (was $maxConnections)" }
        require(maxConnections >= minConnections) {
            "maxConnections ($maxConnections) must be >= minConnections ($minConnections)"
        }
        require(acquireTimeout.isPositive()) { "acquireTimeout must be > 0 (was $acquireTimeout)" }
        require(idleTimeout.isPositive()) { "idleTimeout must be > 0 (was $idleTimeout)" }
        require(cleanupInterval.isPositive()) { "cleanupInterval must be > 0 (was $cleanupInterval)" }
        require(shutdownTimeout.isPositive()) { "shutdownTimeout must be > 0 (was $shutdownTimeout)" }
        require(validateAfterIdle.isPositive()) {
            "validateAfterIdle must be > 0 (was $validateAfterIdle) — use validationQuery = null to disable validation"
        }
    }
}
