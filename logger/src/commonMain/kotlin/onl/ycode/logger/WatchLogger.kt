// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

import onl.ycode.logger.WatchLogger.Watcher

/**
 * This is a proxy logger that uses another logger for logging and
 * also forwards the log messages to a @[Watcher].
 */
class WatchLogger
/**
 * Create a new watch logger.
 *
 * @param logger  the logger to use for logging
 * @param watcher the watcher to forward log messages to
 */
    (private val logger: Logger, private val watcher: Watcher) : Logger {

    /**
     * A functional interface to redirect all log messages to.
     */
    fun interface Watcher {
        /**
         * Watch a log message.
         *
         * @param level     the log level
         * @param message   the log message
         * @param throwable the exception to log, if any
         */
        fun watch(level: String, message: String, throwable: Throwable?)
    }

    override fun debug(message: String, throwable: Throwable?, vararg args: Any?) {
        logger.debug(message, throwable, *args)
        watcher.watch(LogLevel.DEBUG.name, format(message, args), throwable)
    }

    override fun info(message: String, throwable: Throwable?, vararg args: Any?) {
        logger.info(message, throwable, *args)
        watcher.watch(LogLevel.INFO.name, format(message, args), throwable)
    }

    override fun warn(message: String, throwable: Throwable?, vararg args: Any?) {
        logger.warn(message, throwable, *args)
        watcher.watch(LogLevel.WARN.name, format(message, args), throwable)
    }

    override fun error(message: String, throwable: Throwable?, vararg args: Any?) {
        logger.error(message, throwable, *args)
        watcher.watch(LogLevel.ERROR.name, format(message, args), throwable)
    }

    override fun fatal(message: String, throwable: Throwable?, vararg args: Any?) {
        logger.fatal(message, throwable, *args)
        watcher.watch(LogLevel.FATAL.name, format(message, args), throwable)
    }
}
