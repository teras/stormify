// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.logger;

/**
 * LogManager is a factory class for creating Logger instances. Use this class to create a new Logger.
 */
public class LogManager {

    /**
     * Create a new Logger instance for the given class.
     *
     * @param clazz The class for which the Logger should be created.
     * @return A new Logger instance.
     */
    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz, null);
    }

    /**
     * Create a new Logger instance for the given class.
     *
     * @param clazz              The class for which the Logger should be created.
     * @param preferredFramework The preferred logging framework to use.
     * @return A new Logger instance.
     */
    public static Logger getLogger(Class<?> clazz, LogFramework preferredFramework) {
        if (preferredFramework != null) {
            Logger logger = preferredFramework.getLogger(clazz);
            if (logger != null) return logger;
        }
        for (LogFramework framework : LogFramework.values()) {
            Logger logger = framework.getLogger(clazz);
            if (logger != null) return logger;
        }
        // We should never reach this point, since SystemOutLogger should always be available.
        throw new IllegalStateException("No logging framework available.");
    }

    /**
     * Create a new Logger instance for the given name.
     *
     * @param name The name for which the Logger should be created.
     * @return A new Logger instance.
     */
    public static Logger getLogger(String name) {
        return getLogger(name, null);
    }

    /**
     * Create a new Logger instance for the given name.
     *
     * @param name               The name for which the Logger should be created.
     * @param preferredFramework The preferred logging framework to use.
     * @return A new Logger instance.
     */
    public static Logger getLogger(String name, LogFramework preferredFramework) {
        if (preferredFramework != null) {
            Logger logger = preferredFramework.getLogger(name);
            if (logger != null) return logger;
        }
        for (LogFramework framework : LogFramework.values()) {
            Logger logger = framework.getLogger(name);
            if (logger != null) return logger;
        }
        // We should never reach this point, since SystemOutLogger should always be available.
        throw new IllegalStateException("No logging framework available.");
    }
}
