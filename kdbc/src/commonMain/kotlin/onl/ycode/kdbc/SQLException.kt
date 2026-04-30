// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

/**
 * An exception that is thrown when a database error occurs.
 *
 * This exception is used throughout KDBC and Stormify for all database-related errors:
 * - Connection failures
 * - Query execution errors
 * - Transaction errors
 * - Data type conversion errors
 * - Constraint violations
 *
 * It is a RuntimeException and does not need to be caught, though catching it is recommended
 * for proper error handling.
 *
 * When constructed with a [cause] produced by an underlying driver, [sqlState] and
 * [errorCode] are populated from that driver's vendor-specific fields whenever the
 * platform exposes them (for example, JDBC's `java.sql.SQLException`). For platforms
 * or drivers that do not surface these values, the properties are `null`.
 */
class SQLException : RuntimeException {
    /**
     * The SQLSTATE code reported by the underlying driver, or `null` if the driver
     * does not provide one or no driver-level cause is attached. SQLSTATE is a
     * five-character string defined by the SQL standard and used by most relational
     * databases to classify error conditions in a portable way.
     */
    val sqlState: String?

    /**
     * The vendor-specific error code reported by the underlying driver, or `null`
     * if the driver does not provide one or no driver-level cause is attached.
     * The meaning of this value depends on the database vendor (e.g. Oracle ORA
     * codes, MySQL/MariaDB error numbers, PostgreSQL error codes).
     */
    val errorCode: Int?

    /**
     * Creates a new SQLException with a message and no driver metadata.
     *
     * @param message The error message describing what went wrong.
     */
    constructor(message: String?) : super(message) {
        sqlState = null
        errorCode = null
    }

    /**
     * Creates a new SQLException wrapping an underlying [cause]. When the cause
     * is a driver exception that carries vendor metadata, [sqlState] and
     * [errorCode] are extracted from it automatically.
     *
     * @param message The error message describing what went wrong.
     * @param cause   The underlying cause of the error (e.g., JDBC SQLException, native driver error).
     */
    constructor(message: String?, cause: Throwable?) : super(message, cause) {
        val (state, code) = extractDriverMetadata(cause)
        sqlState = state
        errorCode = code
    }

    /**
     * Creates a new SQLException with explicit driver metadata. Used by KDBC's
     * platform-specific code paths that already know the vendor-reported
     * [sqlState] and [errorCode] (e.g. native drivers reading them directly
     * through their C API).
     *
     * @param message   The error message describing what went wrong.
     * @param cause     The underlying cause of the error, or `null` when the error
     *                  is raised without a wrapped exception.
     * @param sqlState  The SQLSTATE code reported by the driver, or `null`.
     * @param errorCode The vendor-specific error code reported by the driver, or `null`.
     */
    constructor(message: String?, cause: Throwable?, sqlState: String?, errorCode: Int?)
        : super(message, cause) {
        this.sqlState = sqlState
        this.errorCode = errorCode
    }
}

/**
 * Walks the cause chain of [cause] and returns the first driver-level
 * `(sqlState, errorCode)` pair the platform can extract. Returns `(null, null)`
 * when no driver metadata is reachable. Implemented per platform: JVM and Android
 * unwrap `java.sql.SQLException`; native targets currently return `(null, null)`
 * (driver-side extraction lives in the C layer and is wired separately).
 */
internal expect fun extractDriverMetadata(cause: Throwable?): Pair<String?, Int?>

/**
 * Safe cast helper for parameter validation.
 *
 * Performs a safe cast with descriptive error messages for type mismatches.
 * Used throughout KDBC for validating parameter types before use.
 *
 * @param T The target type to cast to (automatically derived from reified type)
 * @return The safely cast value
 * @throws SQLException if the cast fails
 */
inline fun <reified T> Any?.safeCast(): T =
    this as? T ?: throw SQLException("Expected ${T::class.simpleName} but got ${this?.let { it::class.simpleName } ?: "null"}")
