// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.helpers.NOPLoggerFactory

internal class SLF4JLogger : onl.ycode.logger.Logger {
    private val logger: Logger

    internal constructor(clazz: Class<*>?) : super() {
        logger = LoggerFactory.getLogger(clazz)
    }

    internal constructor(name: String?) : super() {
        logger = LoggerFactory.getLogger(name)
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
            LogLevel.FATAL -> logger.error(format(message, *args), throwable)
        }
    }

    companion object {
        init {
            // SLF4J without a backend prints a warning to System.err when it initializes,
            // and then discards every message. Look for a backend first, the way SLF4J
            // itself does, so that SLF4J is not even initialized when there is none: a
            // service provider (2.x), an explicit provider property (2.x), or a static
            // binder (1.x). Then reject a backend that discards everything (slf4j-nop).
            val loader = LoggerFactory::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
            val hasBackend = loader.getResource("META-INF/services/org.slf4j.spi.SLF4JServiceProvider") != null ||
                    !System.getProperty("slf4j.provider").isNullOrBlank() ||
                    loader.getResource("org/slf4j/impl/StaticLoggerBinder.class") != null
            check(hasBackend) { "No SLF4J backend found." }
            check(LoggerFactory.getILoggerFactory() !is NOPLoggerFactory) { "SLF4J discards all messages (NOP backend)." }
        }
    }
}
