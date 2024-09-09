// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.logger;

import java.text.SimpleDateFormat;
import java.util.Date;

import static java.util.Objects.requireNonNull;

/**
 * A logger that logs to the system output.
 */
public class SystemOutLogger implements GenericLogger {

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final String name;

    /**
     * Create a logger for the given class.
     *
     * @param clazz the class to create a logger for
     */
    public SystemOutLogger(Class<?> clazz) {
        requireNonNull(clazz, "Logger class must not be null");
        name = clazz.getName();
    }

    /**
     * Create a logger for the given name.
     *
     * @param name the name to create a logger for
     */
    public SystemOutLogger(String name) {
        requireNonNull(name, "Logger name must not be null");
        this.name = name;
    }

    @Override
    public void debug(String message) {
        log("DEBUG", message, null);
    }

    @Override
    public void debug(String message, Throwable throwable) {
        log("DEBUG", message, throwable);
    }

    @Override
    public void info(String message) {
        log("INFO", message, null);
    }

    @Override
    public void info(String message, Throwable throwable) {
        log("INFO", message, throwable);
    }

    @Override
    public void warn(String message) {
        log("WARN", message, null);
    }

    @Override
    public void warn(String message, Throwable throwable) {
        log("WARN", message, throwable);
    }

    @Override
    public void error(String message) {
        log("ERROR", message, null);
    }

    @Override
    public void error(String message, Throwable throwable) {
        log("ERROR", message, throwable);
    }

    @Override
    public void fatal(String message) {
        log("FATAL", message, null);
    }

    @Override
    public void fatal(String message, Throwable throwable) {
        log("FATAL", message, throwable);
    }

    private void log(String level, String message, Throwable th) {
        System.out.printf("%s [%s] %s: %s%n", dateFormat.format(new Date()), level, name, message);
        if (th != null)
            logException(th, "");
    }

    private void logException(Throwable th, String from) {
        System.out.println(from + th);
        for (StackTraceElement ste : th.getStackTrace())
            if (!(ste.getClassName().startsWith("java.")
                    || ste.getClassName().startsWith("sun.")
                    || ste.getClassName().startsWith("com.sun.")
                    || ste.getClassName().startsWith("org.apache.catalina.")
                    || ste.getClassName().startsWith("org.apache.tomcat.")
                    || ste.getClassName().startsWith("org.apache.jasper.")
                    || ste.getClassName().startsWith("worker.org.gradle.")))
                System.out.println("\tat " + ste);
        Throwable child = th.getCause();
        if (child != null && !child.equals(th))
            logException(child, "caused by ");
    }
}
