package test

import onl.ycode.stormify.Stormify

object TestHelper {
    fun withDb(testName: String, test: (Stormify) -> Unit) {
        val databases = createTestDatabases()
        if (databases.isEmpty()) {
            println("Skipping: No test databases configured")
            return
        }
        databases.forEach { testDb ->
            println("[$testName] Running on: ${testDb.name}")
            val s = Stormify(testDb.dataSource)
            s.isStrictMode = false
            s.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
            TestDDL.init(s)
            try {
                test(s)
                println("[$testName] PASSED on ${testDb.name}")
            } catch (e: Throwable) {
                println("[$testName] FAILED on ${testDb.name}: ${e.message}")
                throw AssertionError("Test failed on ${testDb.name}: ${e.message}", e)
            }
        }
    }
}
