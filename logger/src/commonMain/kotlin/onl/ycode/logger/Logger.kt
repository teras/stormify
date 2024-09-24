// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

/**
 * A common interface wrapper for all logging frameworks.
 */
interface Logger {
    /**
     * Log a debug message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    fun debug(message: String, vararg args: Any?) = debug(message, null, *args)

    /**
     * Log a debug message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    fun debug(message: String, throwable: Throwable?, vararg args: Any?)

    /**
     * Log an info message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    fun info(message: String, vararg args: Any?) = info(message, null, *args)

    /**
     * Log an info message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    fun info(message: String, throwable: Throwable?, vararg args: Any?)

    /**
     * Log a warning message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    fun warn(message: String, vararg args: Any?) = warn(message, null, *args)

    /**
     * Log a warning message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    fun warn(message: String, throwable: Throwable?, vararg args: Any?)

    /**
     * Log an error message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    fun error(message: String, vararg args: Any?) = error(message, null, *args)

    /**
     * Log an error message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    fun error(message: String, throwable: Throwable?, vararg args: Any?)

    /**
     * Log a fatal message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    fun fatal(message: String, vararg args: Any?) = fatal(message, null, *args)

    /**
     * Log a fatal message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    fun fatal(message: String, throwable: Throwable?, vararg args: Any?)
}
