// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.logger;

/**
 * This is a proxy logger that uses another logger for logging and
 * also forwards the log messages to a @{@link Watcher}.
 */
public class WatchLogger implements GenericLogger {
    private static final String DEBUG = "DEBUG";
    private static final String INFO = "INFO";
    private static final String WARN = "WARN";
    private static final String ERROR = "ERROR";
    private static final String FATAL = "FATAL";

    private final Logger logger;
    private final Watcher watcher;

    /**
     * A functional interface to redirect all log messages to.
     */
    public interface Watcher {
        /**
         * Watch a log message.
         *
         * @param level     the log level
         * @param message   the log message
         * @param throwable the exception to log, if any
         */
        void watch(String level, String message, Throwable throwable);
    }

    /**
     * Create a new watch logger.
     *
     * @param logger  the logger to use for logging
     * @param watcher the watcher to forward log messages to
     */
    public WatchLogger(Logger logger, Watcher watcher) {
        this.logger = logger;
        this.watcher = watcher;
    }

    @Override
    public void debug(String message) {
        logger.debug(message);
        watcher.watch(DEBUG, message, null);
    }

    @Override
    public void debug(String message, Throwable throwable) {
        logger.debug(message, throwable);
        watcher.watch(DEBUG, message, throwable);
    }

    @Override
    public void debug(String message, Object... args) {
        logger.debug(message, args);
        watcher.watch(DEBUG, format(message, args), null);
    }

    @Override
    public void debug(String message, Throwable throwable, Object... args) {
        logger.debug(message, throwable, args);
        watcher.watch(DEBUG, format(message, args), throwable);
    }

    @Override
    public void info(String message) {
        logger.info(message);
        watcher.watch(INFO, message, null);
    }

    @Override
    public void info(String message, Throwable throwable) {
        logger.info(message, throwable);
        watcher.watch(INFO, message, throwable);
    }

    @Override
    public void info(String message, Object... args) {
        logger.info(message, args);
        watcher.watch(INFO, format(message, args), null);
    }

    @Override
    public void info(String message, Throwable throwable, Object... args) {
        logger.info(message, throwable, args);
        watcher.watch(INFO, format(message, args), throwable);
    }

    @Override
    public void warn(String message) {
        logger.warn(message);
        watcher.watch(WARN, message, null);
    }

    @Override
    public void warn(String message, Throwable throwable) {
        logger.warn(message, throwable);
        watcher.watch(WARN, message, throwable);
    }

    @Override
    public void warn(String message, Object... args) {
        logger.warn(message, args);
        watcher.watch(WARN, format(message, args), null);
    }

    @Override
    public void warn(String message, Throwable throwable, Object... args) {
        logger.warn(message, throwable, args);
        watcher.watch(WARN, format(message, args), throwable);
    }

    @Override
    public void error(String message) {
        logger.error(message);
        watcher.watch(ERROR, message, null);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.error(message, throwable);
        watcher.watch(ERROR, message, throwable);
    }

    @Override
    public void error(String message, Object... args) {
        logger.error(message, args);
        watcher.watch(ERROR, format(message, args), null);
    }

    @Override
    public void error(String message, Throwable throwable, Object... args) {
        logger.error(message, throwable, args);
        watcher.watch(ERROR, format(message, args), throwable);
    }

    @Override
    public void fatal(String message) {
        logger.fatal(message);
        watcher.watch(FATAL, message, null);
    }

    @Override
    public void fatal(String message, Throwable throwable) {
        logger.fatal(message, throwable);
        watcher.watch(FATAL, message, throwable);
    }

    @Override
    public void fatal(String message, Object... args) {
        logger.fatal(message, args);
        watcher.watch(FATAL, format(message, args), null);
    }

    @Override
    public void fatal(String message, Throwable throwable, Object... args) {
        logger.fatal(message, throwable, args);
        watcher.watch(FATAL, format(message, args), throwable);
    }
}
