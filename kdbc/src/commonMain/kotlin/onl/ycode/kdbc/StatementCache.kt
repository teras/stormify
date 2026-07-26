// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

/**
 * Per-connection LRU cache of [Statement] handles, keyed by the exact SQL string.
 *
 * Lives on a single [Connection] for that connection's whole lifetime — created when the
 * connection is opened, closed when the connection is closed. Repeated calls to
 * [Connection.acquirePreparedStatement] on the same SQL return the same cached statement
 * (after [Statement.reset]) instead of paying the cost of preparing it anew. On the JVM
 * this avoids a JDBC `prepareStatement` call into the driver; on native it avoids a
 * `kdbc_prepare` C call which on PostgreSQL costs a server-side `Parse` round-trip.
 *
 * Cached statements are reused via [Statement.reset], which clears bound parameters and
 * any pending batch entries. The cache is **single-thread-of-control by construction** —
 * a [Connection] is used by one thread at a time per the JDBC invariant, so the cache
 * itself does not need synchronization.
 *
 * When the cache exceeds its configured size, the least-recently-released entry is closed.
 * On [closeAll] every cached statement is physically closed; this is invoked from
 * [Connection.close] so a closed connection has no lingering statement handles.
 *
 * Statements that need driver-specific generated-key state (`returnGeneratedKeys=true` or
 * an explicit `columnNames` array) are intentionally not routed through this cache and
 * instead go through [Connection.initStatement] each time — they are typically one-shot
 * inserts where caching has limited value and the driver-side state machine is delicate.
 */
internal class StatementCache(private val maxSize: Int = 64) {
    private val pool = LinkedHashMap<String, ArrayDeque<Statement>>()
    private var totalCount = 0
    private var closed = false

    fun acquire(connection: Connection, sql: String): Statement {
        if (closed || maxSize <= 0) return connection.initStatement(sql, false, null)
        val q = pool[sql]
        if (q != null && q.isNotEmpty()) {
            val s = q.removeFirst()
            totalCount--
            // If reset fails the statement is unusable — close it and prepare fresh.
            val reset = runCatching { s.reset() }
            if (reset.isFailure) {
                runCatching { s.close() }
                return CachedStatement(sql, connection.initStatement(sql, false, null), this)
            }
            return CachedStatement(sql, s, this)
        }
        return CachedStatement(sql, connection.initStatement(sql, false, null), this)
    }

    fun release(sql: String, stmt: Statement) {
        if (closed || maxSize <= 0) {
            runCatching { stmt.close() }
            return
        }
        // fetchSize is not covered by Statement.reset — a cursor query's fetch size
        // (e.g. MySQL's Int.MIN_VALUE streaming switch) would otherwise leak into
        // the next borrower of the same SQL. 0 restores the driver default.
        runCatching { stmt.setFetchSize(0) }
        // LRU touch: remove and re-insert the SQL bucket so iteration finds the
        // least-recently-released bucket first. (Multiplatform LinkedHashMap doesn't
        // expose the JVM-only accessOrder constructor.)
        val q = pool.remove(sql) ?: ArrayDeque()
        q.addLast(stmt)
        pool[sql] = q
        totalCount++
        while (totalCount > maxSize) {
            val it = pool.entries.iterator()
            if (!it.hasNext()) break
            val entry = it.next()
            val oldestKey = entry.key
            val oldestQ = entry.value
            val evict = oldestQ.removeFirst()
            totalCount--
            runCatching { evict.close() }
            if (oldestQ.isEmpty()) pool.remove(oldestKey)
        }
    }

    fun closeAll() {
        if (closed) return
        closed = true
        for (q in pool.values)
            for (s in q) runCatching { s.close() }
        pool.clear()
        totalCount = 0
    }
}

/**
 * Wrapper that intercepts [close] to return the underlying [delegate] to the cache instead
 * of physically closing it. All other [Statement] methods delegate verbatim.
 */
private class CachedStatement(
    val sql: String,
    val delegate: Statement,
    val cache: StatementCache,
) : Statement {
    override fun setObject(parameterIndex: Int, value: Any?) = delegate.setObject(parameterIndex, value)
    override fun executeUpdate(): Int = delegate.executeUpdate()
    override fun executeQuery(): ResultSet = delegate.executeQuery()
    override fun getGeneratedKeys(): ResultSet = delegate.getGeneratedKeys()
    override fun addBatch() = delegate.addBatch()
    override fun executeBatch(): IntArray = delegate.executeBatch()
    override fun reset() = delegate.reset()
    override fun setFetchSize(rows: Int) = delegate.setFetchSize(rows)
    override fun close() {
        // Do not physically close — return to the cache for the next acquire.
        cache.release(sql, delegate)
    }
}
