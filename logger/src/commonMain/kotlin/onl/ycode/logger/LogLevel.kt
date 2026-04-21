package onl.ycode.logger

/**
 * Log severity levels, ordered from least to most severe.
 * Used by [Logger.level] to control the minimum visible log level.
 */
enum class LogLevel {
    /** Fine-grained diagnostic information, typically for development. */
    DEBUG,
    /** General operational messages confirming normal behavior. */
    INFO,
    /** Potentially harmful conditions that deserve attention. */
    WARN,
    /** Error events that might still allow the application to continue. */
    ERROR,
    /** Severe errors that will likely cause the application to abort. */
    FATAL
}
