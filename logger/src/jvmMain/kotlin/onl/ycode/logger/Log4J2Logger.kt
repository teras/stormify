// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * A logger based on Log4J2.
 */
internal class Log4J2Logger : onl.ycode.logger.Logger {
    private val logger: Logger

    /**
     * Create a logger for the given class.
     *
     * @param clazz the class to create a logger for
     */
    internal constructor(clazz: Class<*>?) : super() {
        logger = LogManager.getLogger(clazz)
    }

    /**
     * Create a logger for the given name.
     *
     * @param name the name to create a logger for
     */
    internal constructor(name: String?) : super() {
        logger = LogManager.getLogger(name)
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
