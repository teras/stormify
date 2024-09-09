// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.logger;

/**
 * A logger that does not log anything.
 */
public class SilentLogger implements Logger {

    @Override
    public void debug(String message) {
    }

    @Override
    public void debug(String message, Throwable throwable) {
    }

    @Override
    public void debug(String message, Object... args) {
    }

    @Override
    public void debug(String message, Throwable throwable, Object... args) {
    }

    @Override
    public void info(String message) {
    }

    @Override
    public void info(String message, Throwable throwable) {
    }

    @Override
    public void info(String message, Object... args) {
    }

    @Override
    public void info(String message, Throwable throwable, Object... args) {
    }

    @Override
    public void warn(String message) {
    }

    @Override
    public void warn(String message, Throwable throwable) {
    }

    @Override
    public void warn(String message, Object... args) {
    }

    @Override
    public void warn(String message, Throwable throwable, Object... args) {
    }

    @Override
    public void error(String message) {
    }

    @Override
    public void error(String message, Throwable throwable) {
    }

    @Override
    public void error(String message, Object... args) {
    }

    @Override
    public void error(String message, Throwable throwable, Object... args) {
    }

    @Override
    public void fatal(String message) {
    }

    @Override
    public void fatal(String message, Throwable throwable) {
    }

    @Override
    public void fatal(String message, Object... args) {
    }

    @Override
    public void fatal(String message, Throwable throwable, Object... args) {
    }
}
