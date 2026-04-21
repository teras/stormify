// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A logging backend that the [LogManager] can route through. Five built-in
 * backends ship with the library: [SLF4J], [LOG4J], [LOG4J2],
 * [APACHE_COMMONS], and [SYSTEM_OUT]. Additional backends (Sentry,
 * OpenTelemetry, custom sinks, …) are attached at runtime via [register].
 *
 * `LogManager.getLogger(...)` walks [entries] in order and returns the first
 * backend whose factory succeeds. Registered custom backends are tried before
 * the built-in ones so they can override them; to force a specific backend,
 * pass it as `preferredFramework`.
 *
 * Example:
 * ```kotlin
 * object SentryBackend : LogFramework("Sentry") {
 *     override fun getLogger(clazz: Class<*>?): Logger = SentryLogger(clazz)
 *     override fun getLogger(name: String?): Logger = SentryLogger(name)
 * }
 *
 * LogFramework.register(SentryBackend)
 * ```
 */
abstract class LogFramework protected constructor(
    /** Human-readable identifier used in diagnostic messages. */
    val name: String
) {
    /** Creates a [Logger] for the given [clazz], or null if this backend cannot serve it. */
    abstract fun getLogger(clazz: Class<*>?): Logger?

    /** Creates a [Logger] with the given [name], or null if this backend cannot serve it. */
    abstract fun getLogger(name: String?): Logger?

    companion object {
        /** SLF4J backend — used when `org.slf4j:slf4j-api` is on the classpath. */
        @JvmField
        val SLF4J: LogFramework = Slf4jBackend

        /** Legacy Log4J 1.x backend. */
        @JvmField
        val LOG4J: LogFramework = Log4jBackend

        /** Log4J 2.x backend. */
        @JvmField
        val LOG4J2: LogFramework = Log4j2Backend

        /** Apache Commons Logging backend. */
        @JvmField
        val APACHE_COMMONS: LogFramework = ApacheCommonsBackend

        /** `System.out` fallback — always available. */
        @JvmField
        val SYSTEM_OUT: LogFramework = SystemOutBackend

        private val customs: MutableList<LogFramework> = CopyOnWriteArrayList()

        /**
         * Registers a custom backend. Registered backends are tried before the
         * built-in ones (in registration order), so a registered backend that
         * returns a non-null logger effectively overrides the defaults.
         */
        @JvmStatic
        fun register(framework: LogFramework) {
            customs += framework
        }

        /** All registered frameworks in resolution order — customs first, then built-ins. */
        @JvmStatic
        val entries: List<LogFramework>
            get() = customs + listOf(SLF4J, LOG4J, LOG4J2, APACHE_COMMONS, SYSTEM_OUT)
    }
}

private inline fun safely(factory: () -> Logger): Logger? =
    try {
        factory()
    } catch (_: Throwable) {
        null
    }

private object Slf4jBackend : LogFramework("SLF4J") {
    override fun getLogger(clazz: Class<*>?): Logger? = safely { SLF4JLogger(clazz) }
    override fun getLogger(name: String?): Logger? = safely { SLF4JLogger(name) }
}

private object Log4jBackend : LogFramework("LOG4J") {
    override fun getLogger(clazz: Class<*>?): Logger? = safely { Log4JLogger(clazz) }
    override fun getLogger(name: String?): Logger? = safely { Log4JLogger(name) }
}

private object Log4j2Backend : LogFramework("LOG4J2") {
    override fun getLogger(clazz: Class<*>?): Logger? = safely { Log4J2Logger(clazz) }
    override fun getLogger(name: String?): Logger? = safely { Log4J2Logger(name) }
}

private object ApacheCommonsBackend : LogFramework("APACHE_COMMONS") {
    override fun getLogger(clazz: Class<*>?): Logger? = safely { ApacheCommonsLogger(clazz) }
    override fun getLogger(name: String?): Logger? = safely { ApacheCommonsLogger(name) }
}

private object SystemOutBackend : LogFramework("SYSTEM_OUT") {
    override fun getLogger(clazz: Class<*>?): Logger = SystemOutLogger(clazz?.name ?: "")
    override fun getLogger(name: String?): Logger = SystemOutLogger(name ?: "")
}
