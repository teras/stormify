package test

import onl.ycode.stormify.Stormify

/** Thrown to indicate the current test should be skipped on the current dialect. */
class SkipException(message: String) : RuntimeException(message)

/**
 * Categorizes why a test is skipped on a given dialect. These are all **explicit,
 * deliberate decisions** — not "we forgot to implement this" markers.
 */
enum class SkipReason(val tag: String) {
    /** The DB fundamentally lacks this feature. Cannot be worked around. */
    DB_LIMITATION("db-limit"),

    /** Feature exists only in newer versions of this DB. */
    VERSION_LIMIT("version-limit"),

    /** Dialect-specific quirk (different defaults, syntax, etc.) that we explicitly
     *  chose NOT to work around — the feature is DB-specific and outside our scope. */
    DIALECT_QUIRK("dialect-quirk"),

    /** Library-level limitation — our own choice in stormify/kdbc. Could be fixed. */
    LIBRARY_LIMITATION("library-limit"),

    /** Feature doesn't exist on this DB — nothing to test. */
    FEATURE_NA("feature-n/a"),
}

/** Throws [SkipException] to skip the test with a categorized reason. */
fun skipTest(reason: SkipReason, detail: String): Nothing =
    throw SkipException("[${reason.tag}] $detail")

object TestHelper {
    /** Max blob size for the currently running test database (0 = unlimited). */
    var maxBlobTestSize: Int = 0
        private set

    fun withDb(testName: String, test: (Stormify) -> Unit) {
        val databases = createTestDatabases()
        if (databases.isEmpty()) {
            println("Skipping: No test databases configured")
            return
        }
        databases.forEach { testDb ->
            println("[$testName] Running on: ${testDb.name}")
            maxBlobTestSize = testDb.maxBlobTestSize
            val s = Stormify(testDb.dataSource)
            s.isStrictMode = false
            s.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
            TestDDL.init(s)
            try {
                test(s)
                println("[$testName] PASSED on ${testDb.name}")
            } catch (e: SkipException) {
                println("[$testName] SKIPPED on ${testDb.name}: ${e.message}")
            } catch (e: Throwable) {
                println("[$testName] FAILED on ${testDb.name}: ${e.message}")
                throw AssertionError("Test failed on ${testDb.name}: ${e.message}", e)
            } finally {
                testDb.close?.invoke()
            }
        }
    }
}
