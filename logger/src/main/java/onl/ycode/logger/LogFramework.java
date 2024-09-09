// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.logger;

import java.util.function.Function;

/**
 * A list of available logging frameworks.
 */
public enum LogFramework {
    /**
     * Use SLF4J for logging.
     */
    SLF4J(SLF4JLogger::new, SLF4JLogger::new),
    /**
     * Use legacy Log4J for logging.
     */
    LOG4J(Log4JLogger::new, Log4JLogger::new),
    /**
     * Use Log4J2 for logging.
     */
    LOG4J2(Log4J2Logger::new, Log4J2Logger::new),
    /**
     * Use Apache Commons Logging for logging.
     */
    APACHE_COMMONS(ApacheCommonsLogger::new, ApacheCommonsLogger::new),
    /**
     * Use System.out for logging.
     */
    SYSTEM_OUT(SystemOutLogger::new, SystemOutLogger::new);

    private final Function<Class<?>, Logger> byClass;
    private final Function<String, Logger> byName;

    LogFramework(Function<Class<?>, Logger> byClass, Function<String, Logger> byName) {
        this.byClass = byClass;
        this.byName = byName;
    }

    Logger getLogger(Class<?> clazz) {
        try {
            return byClass.apply(clazz);
        } catch (Throwable ignored) {
            return null;
        }
    }

    Logger getLogger(String name) {
        try {
            return byName.apply(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
