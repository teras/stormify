// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Stormify
import kotlin.test.*

/**
 * Common test suite that runs on all platforms (JVM and Native).
 * Tests basic CRUD operations against different database backends.
 */
class SharedTestSuite {

    private fun runTestOnDatabase(testDb: TestDatabase, testName: String, test: (Stormify) -> Unit) {
        println("[$testName] Running on: ${testDb.name}")
        try {
            val stormify = Stormify(testDb.dataSource)
            test(stormify)
            println("[$testName] ✓ PASSED on ${testDb.name}")
        } catch (e: Throwable) {
            println("[$testName] ✗ FAILED on ${testDb.name}: ${e.message}")
            e.printStackTrace()
            throw AssertionError("Test failed on ${testDb.name}: ${e.message}", e)
        }
    }

    @Test
    fun testCreateTable() {
        val databases = createTestDatabases()
        if (databases.isEmpty()) {
            println("Skipping test: No test databases configured")
            return
        }

        databases.forEach { testDb ->
            runTestOnDatabase(testDb, "CREATE TABLE") { stormify ->
                stormify.transaction {
                    // Create table
                    executeUpdate("CREATE TABLE IF NOT EXISTS user (id INTEGER PRIMARY KEY, name TEXT NOT NULL, email TEXT NOT NULL, age INTEGER)")

                    // Verify table exists by trying to query it
                    val count = readOne<Int>("SELECT COUNT(*) FROM user")
                    assertEquals(0, count, "New table should be empty")
                }
            }
        }
    }

    @Test
    fun testInsertAndRead() {
        val databases = createTestDatabases()
        if (databases.isEmpty()) {
            println("Skipping test: No test databases configured")
            return
        }

        databases.forEach { testDb ->
            runTestOnDatabase(testDb, "INSERT & READ") { stormify ->
                stormify.transaction {
                    // Setup table
                    executeUpdate("""
                        CREATE TABLE IF NOT EXISTS user (
                            id INTEGER PRIMARY KEY,
                            name TEXT NOT NULL,
                            email TEXT NOT NULL,
                            age INTEGER
                        )
                    """.trimIndent())

                    // Clear any existing data
                    executeUpdate("DELETE FROM user")

                    // Insert test data
                    executeUpdate("INSERT INTO user (id, name, email, age) VALUES (?, ?, ?, ?)",
                        1, "Alice", "alice@example.com", 30)
                    executeUpdate("INSERT INTO user (id, name, email, age) VALUES (?, ?, ?, ?)",
                        2, "Bob", "bob@example.com", 25)
                    executeUpdate("INSERT INTO user (id, name, email, age) VALUES (?, ?, ?, ?)",
                        3, "Charlie", "charlie@example.com", null)

                    // Read back data
                    val count = readOne<Int>("SELECT COUNT(*) FROM user")
                    assertEquals(3, count, "Should have 3 users")

                    val name = readOne<String>("SELECT name FROM user WHERE id = ?", 1)
                    assertEquals("Alice", name, "User name should match")

                    val age = readOne<Int>("SELECT age FROM user WHERE id = ?", 2)
                    assertEquals(25, age, "User age should match")
                }
            }
        }
    }

    @Test
    fun testUpdate() {
        val databases = createTestDatabases()
        if (databases.isEmpty()) {
            println("Skipping test: No test databases configured")
            return
        }

        databases.forEach { testDb ->
            runTestOnDatabase(testDb, "UPDATE") { stormify ->
                stormify.transaction {
                    // Setup
                    executeUpdate("""
                        CREATE TABLE IF NOT EXISTS user (
                            id INTEGER PRIMARY KEY,
                            name TEXT NOT NULL,
                            email TEXT NOT NULL,
                            age INTEGER
                        )
                    """.trimIndent())
                    executeUpdate("DELETE FROM user")
                    executeUpdate("INSERT INTO user (id, name, email, age) VALUES (?, ?, ?, ?)",
                        1, "Alice", "alice@example.com", 30)

                    // Update
                    executeUpdate("UPDATE user SET age = ? WHERE id = ?", 31, 1)

                    // Verify
                    val age = readOne<Int>("SELECT age FROM user WHERE id = ?", 1)
                    assertEquals(31, age, "Age should be updated")
                }
            }
        }
    }

    @Test
    fun testDelete() {
        val databases = createTestDatabases()
        if (databases.isEmpty()) {
            println("Skipping test: No test databases configured")
            return
        }

        databases.forEach { testDb ->
            runTestOnDatabase(testDb, "DELETE") { stormify ->
                stormify.transaction {
                    // Setup
                    executeUpdate("""
                        CREATE TABLE IF NOT EXISTS user (
                            id INTEGER PRIMARY KEY,
                            name TEXT NOT NULL,
                            email TEXT NOT NULL,
                            age INTEGER
                        )
                    """.trimIndent())
                    executeUpdate("DELETE FROM user")
                    executeUpdate("INSERT INTO user (id, name, email, age) VALUES (?, ?, ?, ?)",
                        1, "Alice", "alice@example.com", 30)
                    executeUpdate("INSERT INTO user (id, name, email, age) VALUES (?, ?, ?, ?)",
                        2, "Bob", "bob@example.com", 25)

                    // Delete one record
                    executeUpdate("DELETE FROM user WHERE id = ?", 1)

                    // Verify
                    val count = readOne<Int>("SELECT COUNT(*) FROM user")
                    assertEquals(1, count, "Should have 1 user remaining")

                    val name = readOne<String>("SELECT name FROM user WHERE id = ?", 2)
                    assertEquals("Bob", name, "Remaining user should be Bob")
                }
            }
        }
    }

    @Test
    fun testTransaction() {
        val databases = createTestDatabases()
        if (databases.isEmpty()) {
            println("Skipping test: No test databases configured")
            return
        }

        databases.forEach { testDb ->
            runTestOnDatabase(testDb, "TRANSACTION") { stormify ->
                // Setup table outside transaction
                stormify.transaction {
                    executeUpdate("""
                        CREATE TABLE IF NOT EXISTS user (
                            id INTEGER PRIMARY KEY,
                            name TEXT NOT NULL,
                            email TEXT NOT NULL,
                            age INTEGER
                        )
                    """.trimIndent())
                    executeUpdate("DELETE FROM user")
                }

                // Test transaction rollback
                try {
                    stormify.transaction {
                        executeUpdate("INSERT INTO user (id, name, email, age) VALUES (?, ?, ?, ?)",
                            1, "Alice", "alice@example.com", 30)

                        // Force an error to trigger rollback
                        throw Exception("Intentional rollback")
                    }
                    fail("Should have thrown exception")
                } catch (e: Exception) {
                    // Expected
                }

                // Verify rollback worked
                stormify.transaction {
                    val count = readOne<Int>("SELECT COUNT(*) FROM user")
                    assertEquals(0, count, "Transaction should have been rolled back")
                }
            }
        }
    }
}
