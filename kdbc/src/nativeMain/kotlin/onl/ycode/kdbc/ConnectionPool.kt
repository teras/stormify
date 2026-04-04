package onl.ycode.kdbc

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Configuration for connection pooling.
 */
data class PoolConfig(
    val enabled: Boolean = true,
    val minConnections: Int = 2,
    val maxConnections: Int = 10,
    val connectionTimeout: Duration = 30.seconds,
    val idleTimeout: Duration = 5.minutes,
    val validationQuery: String? = null
) {
    init {
        require(minConnections >= 0) { "minConnections must be >= 0" }
        require(maxConnections >= minConnections) { "maxConnections must be >= minConnections" }
    }
}

/**
 * Abstract base class for DataSource implementations with built-in connection pooling.
 *
 * Subclasses only need to implement [createNewConnection] to provide database-specific
 * connection creation logic. The pooling is handled transparently.
 *
 * Example:
 * ```kotlin
 * class PostgresDataSource(
 *     host: String,
 *     port: Int = 5432,
 *     database: String,
 *     user: String,
 *     password: String,
 *     poolConfig: PoolConfig = PoolConfig()
 * ) : PoolableDataSource(poolConfig) {
 *     private val connectionString = "postgresql://$user:$password@$host:$port/$database"
 *
 *     override fun createNewConnection(): Connection {
 *         return PostgresConnection(connectionString, emptyMap())
 *     }
 * }
 * ```
 */
abstract class PoolableDataSource(
    private val poolConfig: PoolConfig = PoolConfig()
) : DataSource {

    private val pool: ConnectionPool? = if (poolConfig.enabled) {
        ConnectionPool(::createNewConnection, poolConfig)
    } else null

    /**
     * Creates a new database connection. Implemented by subclasses to provide
     * database-specific connection logic.
     */
    protected abstract fun createNewConnection(): Connection

    final override fun getConnection(): Connection {
        return pool?.getConnection() ?: createNewConnection()
    }

    /**
     * Shuts down the connection pool if pooling is enabled.
     */
    fun shutdown() {
        pool?.shutdown()
    }

    /**
     * Returns pool statistics if pooling is enabled, null otherwise.
     */
    fun getPoolStats(): PoolStats? {
        return pool?.getStats()
    }
}

/**
 * Internal connection pool implementation.
 * Used by PoolableDataSource to provide transparent connection pooling.
 */
internal class ConnectionPool(
    private val connectionFactory: () -> Connection,
    private val config: PoolConfig = PoolConfig()
) {
    private val pool = mutableListOf<PoolEntry>()
    private var totalConnections = 0
    private val timeSource = TimeSource.Monotonic
    private val mutex = Mutex()

    init {
        // Pre-populate pool with minimum connections
        repeat(config.minConnections) {
            try {
                pool.add(createPoolEntry())
            } catch (e: Exception) {
                // Log error but continue - we'll create connections on demand
            }
        }
    }

    fun getConnection(): Connection = runBlocking {
        getConnectionSuspend()
    }

    private suspend fun getConnectionSuspend(): Connection {
        mutex.withLock {
            cleanupIdleConnections()

            // Try to find an available connection
            val available = pool.firstOrNull { !it.isInUse }
            if (available != null) {
                if (isValid(available.connection)) {
                    available.isInUse = true
                    available.lastUsed = timeSource.markNow()
                    return PooledConnectionWrapper(available.connection, this@ConnectionPool, available)
                } else {
                    // Connection is invalid, remove and create new one
                    pool.remove(available)
                    try {
                        available.connection.actualClose()
                    } catch (e: Exception) {
                        // Ignore close errors
                    }
                    totalConnections--
                }
            }

            // No available connection, try to create a new one
            if (totalConnections < config.maxConnections) {
                val newEntry = createPoolEntry()
                pool.add(newEntry)
                return PooledConnectionWrapper(newEntry.connection, this@ConnectionPool, newEntry)
            }
        }

        // Pool is exhausted, wait for a connection or timeout
        val waitStart = timeSource.markNow()
        while (true) {
            delay(100.milliseconds) // Wait 100ms before retry

            mutex.withLock {
                val nowAvailable = pool.firstOrNull { !it.isInUse }
                if (nowAvailable != null && isValid(nowAvailable.connection)) {
                    nowAvailable.isInUse = true
                    nowAvailable.lastUsed = timeSource.markNow()
                    return PooledConnectionWrapper(nowAvailable.connection, this@ConnectionPool, nowAvailable)
                }
            }

            if (waitStart.elapsedNow() > config.connectionTimeout) {
                throw SQLException("Connection pool exhausted - timeout waiting for connection")
            }
        }
    }

    private fun createPoolEntry(): PoolEntry {
        val conn = connectionFactory()
        totalConnections++
        return PoolEntry(conn).apply {
            isInUse = true
            lastUsed = timeSource.markNow()
        }
    }

    private fun cleanupIdleConnections() {
        val now = timeSource.markNow()
        val toRemove = pool.filter { !it.isInUse && (now - it.lastUsed) > config.idleTimeout }

        // Keep at least minConnections
        val canRemove = (pool.size - toRemove.size) >= config.minConnections
        if (canRemove) {
            toRemove.forEach { entry ->
                pool.remove(entry)
                try {
                    entry.connection.actualClose()
                } catch (e: Exception) {
                    // Ignore close errors
                }
                totalConnections--
            }
        }
    }

    internal fun releaseConnection(entry: PoolEntry) = runBlocking {
        mutex.withLock {
            entry.isInUse = false
            entry.lastUsed = timeSource.markNow()
        }
    }

    private fun isValid(connection: Connection): Boolean {
        return try {
            if (config.validationQuery != null) {
                connection.initStatement(config.validationQuery, false, null).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun shutdown() = runBlocking {
        mutex.withLock {
            pool.forEach { entry ->
                try {
                    entry.connection.actualClose()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
            pool.clear()
            totalConnections = 0
        }
    }

    fun getStats(): PoolStats = runBlocking {
        mutex.withLock {
            PoolStats(
                totalConnections = totalConnections,
                activeConnections = pool.count { it.isInUse },
                idleConnections = pool.count { !it.isInUse },
                maxConnections = config.maxConnections,
                minConnections = config.minConnections
            )
        }
    }
}

/**
 * Statistics about a connection pool.
 */
data class PoolStats(
    val totalConnections: Int,
    val activeConnections: Int,
    val idleConnections: Int,
    val maxConnections: Int,
    val minConnections: Int
)

/**
 * Pool entry holding a connection and its metadata.
 */
internal class PoolEntry(
    val connection: Connection
) {
    var isInUse = false
    var lastUsed = TimeSource.Monotonic.markNow()
}

/**
 * Wrapper that intercepts close() to return connection to pool.
 */
private class PooledConnectionWrapper(
    internal val delegate: Connection,
    private val pool: ConnectionPool,
    private val entry: PoolEntry
) : Connection by delegate {
    override fun close() {
        // Don't actually close - return to pool
        pool.releaseConnection(entry)
    }

    fun actualClose() {
        // Call the real close() method on the delegate
        (delegate as AutoCloseable).close()
    }
}

/**
 * Extension to actually close a connection (bypassing pooling).
 */
private fun Connection.actualClose() {
    when (this) {
        is PooledConnectionWrapper -> this.actualClose()
        else -> (this as AutoCloseable).close()
    }
}
