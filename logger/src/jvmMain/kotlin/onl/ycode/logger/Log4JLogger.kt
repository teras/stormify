// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

import org.apache.log4j.LogManager
import org.apache.log4j.Logger

/**
 * A logger based on Log4J.
 */
internal class Log4JLogger : onl.ycode.logger.Logger {
    private val logger: Logger

    internal constructor(clazz: Class<*>?) : super() {
        logger = LogManager.getLogger(clazz)
    }

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
