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
 */
class SQLException : RuntimeException {
    /**
     * Creates a new SQLException with a message.
     *
     * @param message The error message describing what went wrong.
     */
    constructor(message: String?) : super(message)

    /**
     * Creates a new SQLException with a message and a cause.
     *
     * @param message The error message describing what went wrong.
     * @param cause   The underlying cause of the error (e.g., JDBC SQLException, native driver error).
     */
    constructor(message: String?, cause: Throwable?) : super(message, cause)
}
