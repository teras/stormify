// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.logger;

/**
 * A logger based on Log4J2.
 */
public class Log4J2Logger implements GenericLogger {
    private final org.apache.logging.log4j.Logger logger;

    /**
     * Create a logger for the given class.
     *
     * @param clazz the class to create a logger for
     */
    public Log4J2Logger(Class<?> clazz) {
        logger = org.apache.logging.log4j.LogManager.getLogger(clazz);
    }

    /**
     * Create a logger for the given name.
     *
     * @param name the name to create a logger for
     */
    public Log4J2Logger(String name) {
        logger = org.apache.logging.log4j.LogManager.getLogger(name);
    }

    @Override
    public void debug(String message) {
        logger.debug(message);
    }

    @Override
    public void debug(String message, Throwable throwable) {
        logger.debug(message, throwable);
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void info(String message, Throwable throwable) {
        logger.info(message, throwable);
    }

    @Override
    public void warn(String message) {
        logger.warn(message);
    }

    @Override
    public void warn(String message, Throwable throwable) {
        logger.warn(message, throwable);
    }

    @Override
    public void error(String message) {
        logger.error(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.error(message, throwable);
    }

    @Override
    public void fatal(String message) {
        logger.fatal(message);
    }

    @Override
    public void fatal(String message, Throwable throwable) {
        logger.fatal(message, throwable);
    }

    @Override
    public void debug(String message, Object... args) {
        logger.debug(message, args);
    }

    @Override
    public void debug(String message, Throwable throwable, Object... args) {
        logger.debug(message, throwable, args);
    }

    @Override
    public void info(String message, Object... args) {
        logger.info(message, args);
    }

    @Override
    public void info(String message, Throwable throwable, Object... args) {
        logger.info(message, throwable, args);
    }

    @Override
    public void warn(String message, Object... args) {
        logger.warn(message, args);
    }

    @Override
    public void warn(String message, Throwable throwable, Object... args) {
        logger.warn(message, throwable, args);
    }

    @Override
    public void error(String message, Object... args) {
        logger.error(message, args);
    }

    @Override
    public void error(String message, Throwable throwable, Object... args) {
        logger.error(message, throwable, args);
    }

    @Override
    public void fatal(String message, Object... args) {
        logger.fatal(message, args);
    }

    @Override
    public void fatal(String message, Throwable throwable, Object... args) {
        logger.fatal(message, throwable, args);
    }
}
