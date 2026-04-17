// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

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
    (private val logger: Logger, private val watcher: Watcher) : Logger() {

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

    override fun log(level: LogLevel, message: String, throwable: Throwable?, vararg args: Any?) {
        if (level < this.level) return
        logger.log(level, message, throwable, *args)
        watcher.watch(level.name, format(message, *args), throwable)
    }
}
