// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory

/**
 * A logger based on Apache Commons Logging.
 */
internal class ApacheCommonsLogger : Logger {
    private val logger: Log

    /**
     * Create a logger for the given class.
     *
     * @param clazz the class to create a logger for
     */
    internal constructor(clazz: Class<*>?) : super() {
        logger = LogFactory.getLog(clazz)
    }

    /**
     * Create a logger for the given name.
     *
     * @param name the name to create a logger for
     */
    internal constructor(name: String?) : super() {
        logger = LogFactory.getLog(name)
    }


    override fun log(
        level: LogLevel,
        message: String,
        throwable: Throwable?,
        vararg args: Any?
    ) {
        if (level < this.level) return
        when (level) {
            LogLevel.DEBUG -> logger.debug(format(message, *args), throwable)
            LogLevel.INFO -> logger.info(format(message, *args), throwable)
            LogLevel.WARN -> logger.warn(format(message, *args), throwable)
            LogLevel.ERROR -> logger.error(format(message, *args), throwable)
            LogLevel.FATAL -> logger.fatal(format(message, *args), throwable)
        }
    }
}
