// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.logger.LogManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The SLF4J logging backend is used only when SLF4J has a real backend. Detection
 * must not touch the process-wide `System.err`, and when there is no backend it must
 * not initialize SLF4J at all, since that prints a warning to `System.err`.
 *
 * Each case loads the logger module and one combination of SLF4J jars into its own
 * classloader, so SLF4J initializes from scratch every time.
 */
class Slf4jDetectionTest {

    private val jars: List<File> =
        System.getProperty("stormify.test.slf4jJars").orEmpty().split(File.pathSeparator)
            .filter { it.isNotBlank() }.map(::File)

    private fun jar(name: String): URL =
        (jars.firstOrNull { it.name == "$name.jar" } ?: error("$name.jar not provided by the build")).toURI().toURL()

    /** Loads `onl.ycode.logger` and `org.slf4j` from [urls] before the parent, everything else from the parent. */
    private class ChildFirstLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        private fun own(name: String) = name.startsWith("onl.ycode.logger.") || name.startsWith("org.slf4j.")
        private fun ownResource(name: String) = name.startsWith("onl/ycode/logger/") ||
                name.startsWith("org/slf4j/") || name.startsWith("META-INF/services/org.slf4j")

        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            if (!own(name)) return super.loadClass(name, resolve)
            val c = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(c)
            c
        }

        override fun getResource(name: String): URL? =
            if (ownResource(name)) findResource(name) else super.getResource(name)

        override fun getResources(name: String): java.util.Enumeration<URL> =
            if (ownResource(name)) findResources(name) else super.getResources(name)
    }

    private class Outcome(val backendFound: Boolean, val slf4jInitialized: Boolean, val stderr: String)

    private fun probe(vararg jarNames: String): Outcome {
        val loggerClasses = LogManager::class.java.protectionDomain.codeSource.location
        val loader = ChildFirstLoader(arrayOf(loggerClasses) + jarNames.map(::jar), javaClass.classLoader)
        val originalErr = System.err
        val captured = ByteArrayOutputStream()
        val err = PrintStream(captured, true)
        System.setErr(err)
        try {
            val frameworks = loader.loadClass("onl.ycode.logger.LogFramework")
            val slf4j = frameworks.getField("SLF4J").get(null)
            val logger = frameworks.getMethod("getLogger", String::class.java).invoke(slf4j, "probe")
            assertSame(err, System.err, "detection must leave System.err untouched")
            val state = loader.loadClass("org.slf4j.LoggerFactory").getDeclaredField("INITIALIZATION_STATE")
                .apply { isAccessible = true }.getInt(null)
            return Outcome(logger != null, state != 0, captured.toString())
        } finally {
            System.setErr(originalErr)
            loader.close()
        }
    }

    private fun assertNoBackend(outcome: Outcome) {
        assertTrue(!outcome.backendFound, "the SLF4J backend must be rejected")
        assertTrue(!outcome.slf4jInitialized, "SLF4J must not be initialized without a backend")
        assertEquals("", outcome.stderr)
    }

    @Test
    fun apiWithoutBackend17() = assertNoBackend(probe("slf4j-api-1.7.36"))

    @Test
    fun apiWithoutBackend20() = assertNoBackend(probe("slf4j-api-2.0.17"))

    @Test
    fun simpleBackend17() = assertTrue(probe("slf4j-api-1.7.36", "slf4j-simple-1.7.36").backendFound)

    @Test
    fun simpleBackend20() = assertTrue(probe("slf4j-api-2.0.17", "slf4j-simple-2.0.17").backendFound)

    @Test
    fun nopBackend17() = assertTrue(!probe("slf4j-api-1.7.36", "slf4j-nop-1.7.36").backendFound)

    // SLF4J 2.x ignores a 1.x binder: it falls back to NOP, which is rejected. The binder
    // is still a reason to initialize SLF4J, so its warning goes to System.err.
    @Test
    fun api20WithBinder17FallsBackToNop() {
        val outcome = probe("slf4j-api-2.0.17", "slf4j-simple-1.7.36")
        assertTrue(!outcome.backendFound, "a NOP fallback must be rejected")
        assertTrue(outcome.slf4jInitialized)
        assertTrue(outcome.stderr.contains("SLF4J"), "SLF4J reports the ignored binder")
    }

    @Test
    fun nopBackend20() = assertTrue(!probe("slf4j-api-2.0.17", "slf4j-nop-2.0.17").backendFound)
}
