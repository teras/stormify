// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.logger;

/**
 * A common interface wrapper for all logging frameworks.
 */
public interface Logger {

    /**
     * Log a debug message.
     *
     * @param message the message to log
     */
    void debug(String message);

    /**
     * Log a debug message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     */
    void debug(String message, Throwable throwable);

    /**
     * Log a debug message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    void debug(String message, Object... args);

    /**
     * Log a debug message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    void debug(String message, Throwable throwable, Object... args);

    /**
     * Log an info message.
     *
     * @param message the message to log
     */
    void info(String message);

    /**
     * Log an info message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     */
    void info(String message, Throwable throwable);

    /**
     * Log an info message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    void info(String message, Object... args);

    /**
     * Log an info message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    void info(String message, Throwable throwable, Object... args);

    /**
     * Log a warning message.
     *
     * @param message the message to log
     */
    void warn(String message);

    /**
     * Log a warning message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     */
    void warn(String message, Throwable throwable);

    /**
     * Log a warning message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    void warn(String message, Object... args);

    /**
     * Log a warning message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    void warn(String message, Throwable throwable, Object... args);

    /**
     * Log an error message.
     *
     * @param message the message to log
     */
    void error(String message);

    /**
     * Log an error message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     */
    void error(String message, Throwable throwable);

    /**
     * Log an error message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    void error(String message, Object... args);

    /**
     * Log an error message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    void error(String message, Throwable throwable, Object... args);

    /**
     * Log a fatal message.
     *
     * @param message the message to log
     */
    void fatal(String message);

    /**
     * Log a fatal message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     */
    void fatal(String message, Throwable throwable);

    /**
     * Log a fatal message.
     *
     * @param message the message to log
     * @param args    the arguments to replace in the message
     */
    void fatal(String message, Object... args);

    /**
     * Log a fatal message.
     *
     * @param message   the message to log
     * @param throwable the exception to log
     * @param args      the arguments to replace in the message
     */
    void fatal(String message, Throwable throwable, Object... args);
}
