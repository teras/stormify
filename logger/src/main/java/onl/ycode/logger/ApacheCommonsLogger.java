// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.logger;

/**
 * A logger based on Apache Commons Logging.
 */
public class ApacheCommonsLogger implements GenericLogger {
    private final org.apache.commons.logging.Log logger;

    /**
     * Create a logger for the given class.
     *
     * @param clazz the class to create a logger for
     */
    public ApacheCommonsLogger(Class<?> clazz) {
        logger = org.apache.commons.logging.LogFactory.getLog(clazz);
    }

    /**
     * Create a logger for the given name.
     *
     * @param name the name to create a logger for
     */
    public ApacheCommonsLogger(String name) {
        logger = org.apache.commons.logging.LogFactory.getLog(name);
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
}
