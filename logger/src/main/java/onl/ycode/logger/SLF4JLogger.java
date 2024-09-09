// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.logger;

import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * A logger implementation that uses SLF4J.
 */
public class SLF4JLogger implements GenericLogger {
    private final org.slf4j.Logger logger;

    static {
        // Check that an actual logger is properly loaded.
        PrintStream originalErr = System.err;
        try (DummyPrintStream dummy = new DummyPrintStream()) {
            System.setErr(dummy);
            if (LoggerFactory.getLogger("test").getName().equals("NOP"))
                throw new IllegalStateException("No actual SLF4J logger found.");
        } finally {
            System.setErr(originalErr);
        }
    }

    SLF4JLogger(Class<?> clazz) {
        logger = org.slf4j.LoggerFactory.getLogger(clazz);
    }

    SLF4JLogger(String name) {
        logger = org.slf4j.LoggerFactory.getLogger(name);
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
        logger.error(message);
    }

    @Override
    public void fatal(String message, Throwable throwable) {
        logger.error(message, throwable);
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
        logger.error(message, args);
    }

    @Override
    public void fatal(String message, Throwable throwable, Object... args) {
        logger.error(message, throwable, args);
    }

    private static class DummyPrintStream extends PrintStream {
        DummyPrintStream() {
            super(new OutputStream() {
                @Override
                public void write(int b) {
                    // Do nothing - it's a dummy stream.
                }
            });
        }
    }
}
