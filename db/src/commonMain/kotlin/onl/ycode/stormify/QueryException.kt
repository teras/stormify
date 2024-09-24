// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * An exception that is thrown when an error occurs while executing a query.
 *
 *
 * This exception is used throughout Stormify and is not required to be caught. Still it is a good practice to catch it.
 */
class QueryException : RuntimeException {
    /**
     * Create a new QueryException with a message.
     *
     * @param message The message of the exception.
     */
    constructor(message: String?) : super(message)

    /**
     * Create a new QueryException with a message and a cause.
     *
     * @param message The message of the exception.
     * @param cause   The cause of the exception.
     */
    constructor(message: String?, cause: Throwable?) : super(message, cause)
}
