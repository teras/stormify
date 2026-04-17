// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

/**
 * A common abstract wrapper for all logging frameworks.
 *
 * Subclasses only need to implement [log] — the single dispatch point for all
 * log messages. Convenience methods ([debug], [info], [warn], [error], [fatal])
 * delegate through it.
 *
 * The [level] property controls the minimum visible severity. Messages below
 * this level are silently discarded. Defaults to [LogLevel.INFO].
 */
abstract class Logger {

    /**
     * The minimum log level. Messages below this level are silently discarded.
     */
    var level = LogLevel.INFO

    /**
     * Log a message at the given level. Implementations dispatch to the
     * underlying logging framework.
     *
     * @param level     the log level
     * @param message   the message to log
     * @param throwable the exception to log, if any
     * @param args      the arguments to replace in the message
     */
    abstract fun log(level: LogLevel, message: String, throwable: Throwable?, vararg args: Any?)

    /**
     * Log a message at the given level.
     *
     * @param level   the log level
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    fun log(level: LogLevel, message: String, vararg args: Any?) =
        log(level, message, null, *args)

    /**
     * Log a debug message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    fun debug(message: String, vararg args: Any?) =
        log(LogLevel.DEBUG, message, null, *args)

    /**
     * Log a debug message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    fun debug(message: String, throwable: Throwable?, vararg args: Any?) =
        log(LogLevel.DEBUG, message, throwable, *args)

    /**
     * Log an info message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    fun info(message: String, vararg args: Any?) =
        log(LogLevel.INFO, message, null, *args)

    /**
     * Log an info message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    fun info(message: String, throwable: Throwable?, vararg args: Any?) =
        log(LogLevel.INFO, message, throwable, *args)

    /**
     * Log a warning message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    fun warn(message: String, vararg args: Any?) =
        log(LogLevel.WARN, message, null, *args)

    /**
     * Log a warning message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    fun warn(message: String, throwable: Throwable?, vararg args: Any?) =
        log(LogLevel.WARN, message, throwable, *args)

    /**
     * Log an error message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    fun error(message: String, vararg args: Any?) =
        log(LogLevel.ERROR, message, null, *args)

    /**
     * Log an error message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    fun error(message: String, throwable: Throwable?, vararg args: Any?) =
        log(LogLevel.ERROR, message, throwable, *args)

    /**
     * Log a fatal message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    fun fatal(message: String, vararg args: Any?) =
        log(LogLevel.FATAL, message, null, *args)

    /**
     * Log a fatal message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    fun fatal(message: String, throwable: Throwable?, vararg args: Any?) =
        log(LogLevel.FATAL, message, throwable, *args)
}
