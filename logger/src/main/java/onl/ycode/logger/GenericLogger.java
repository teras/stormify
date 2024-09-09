// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.logger;

import java.text.MessageFormat;

/**
 * The is a helper interface, to provide common alternatives to some logging methods.
 */
interface GenericLogger extends Logger {

    default void debug(String message, Object... args) {
        debug(format(message, args));
    }

    default void debug(String message, Throwable throwable, Object... args) {
        debug(format(message, args), throwable);
    }

    default void info(String message, Object... args) {
        info(format(message, args));
    }

    default void info(String message, Throwable throwable, Object... args) {
        info(format(message, args), throwable);
    }

    default void warn(String message, Object... args) {
        warn(format(message, args));
    }

    default void warn(String message, Throwable throwable, Object... args) {
        warn(format(message, args), throwable);
    }

    default void error(String message, Object... args) {
        error(format(message, args));
    }

    default void error(String message, Throwable throwable, Object... args) {
        error(format(message, args), throwable);
    }

    default void fatal(String message, Object... args) {
        fatal(format(message, args));
    }

    default void fatal(String message, Throwable throwable, Object... args) {
        fatal(format(message, args), throwable);
    }

    /**
     * Formats a message with the given arguments.
     *
     * @param message the message to format
     * @param args    the arguments to replace in the message
     * @return the formatted message
     */
    default String format(String message, Object... args) {
        for (int i = 0; i < args.length; i++)
            message = message.replaceFirst("\\{}", "{" + i + "}");
        return MessageFormat.format(message, args);
    }
}
