// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for connection pooling.
 *
 * Applied by [PoolableDataSource] on native targets. JVM users that wrap an existing
 * HikariCP / C3P0 data source via `JdbcDataSource` should configure pooling on their
 * underlying pool — this class has no effect there.
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
