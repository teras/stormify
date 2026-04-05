// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

/**
 * Snapshot of a [SuspendConnectionPool]'s internal counters at a point in time.
 *
 * All fields are read from atomic counters without locking, so they are individually
 * accurate but the set as a whole may not represent a consistent moment — e.g.
 * [inUse] + [idle] may differ slightly from [total] if reads race with a borrow.
 */
public data class PoolStats(
    /** Total connections currently owned by the pool (in-use + idle + being validated). */
    val total: Int,
    /** Connections currently handed out via `use { }`. */
    val inUse: Int,
    /** Connections sitting in the idle queue, ready to be reused. */
    val idle: Int,
    /** Total number of successful acquire calls over the pool's lifetime. */
    val acquireCount: Long,
    /** Total number of times a caller waited because the pool was saturated. */
    val waitedAcquireCount: Long,
    /** Total number of connections evicted because of validation failure or use error. */
    val evictedCount: Long,
    /** Total number of connections retired because they exceeded `maxLifetime`. */
    val retiredCount: Long,
)
