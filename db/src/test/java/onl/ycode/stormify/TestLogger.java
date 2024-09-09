package onl.ycode.stormify;

import onl.ycode.logger.Logger;
import onl.ycode.logger.SilentLogger;
import onl.ycode.logger.WatchLogger;

public class TestLogger implements WatchLogger.Watcher {

    private final Logger origLogger = StormifyManager.stormify().getLogger();
    private final StringBuilder buffer = new StringBuilder();

    // Constructor to set up the logger
    public TestLogger() {
        // Set the Stormify logger to use this TestLogger
        StormifyManager.stormify().setLogger(new WatchLogger(new SilentLogger(), this));
    }

    // Implementation of the WatchLogger.Watcher interface
    @Override
    public void watch(String level, String message, Throwable throwable) {
        buffer.append(message).append("\n");
    }

    public String get() {
        try {
            return buffer.toString();
        } finally {
            buffer.setLength(0); // Clear the buffer after returning the log
        }
    }

    // Method to restore the original logger
    public void close() {
        StormifyManager.stormify().setLogger(origLogger);
    }
}
