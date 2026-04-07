// SPDX-License-Identifier: Apache-2.0
package onl.ycode.kdbc

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun blobRoundTrip() {
        val ds = KdbcDataSource("jdbc:sqlite::memory:")
        ds.getConnection().use { conn ->
            conn.initStatement("CREATE TABLE b(id INTEGER PRIMARY KEY, data BLOB)", false, null).use { it.executeUpdate() }
            val orig = byteArrayOf(0, 1, 2, -1, 127, -128, 42)
            conn.initStatement("INSERT INTO b(id, data) VALUES(1, ?)", false, null).use { s ->
                s.setObject(1, orig)
                s.executeUpdate()
            }
            conn.initStatement("SELECT data FROM b WHERE id = 1", false, null).use { s ->
                s.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val read = rs.getObject(1, ByteArray::class) as ByteArray
                    println("Original: ${orig.toList()}")
                    println("Read:     ${read.toList()}")
                    assertTrue(orig.contentEquals(read))
                }
            }
        }
    }

    @Test
    fun sqliteInMemoryRoundTrip() {
        val ds = KdbcDataSource("jdbc:sqlite::memory:")
        ds.getConnection().use { conn ->
            val meta = conn.metaData
            println("Product: ${meta.databaseProductName} ${meta.databaseProductVersion}")
            conn.initStatement("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)", false, null).use { it.executeUpdate() }
            conn.initStatement("INSERT INTO t(id, name) VALUES (?, ?)", false, null).use { stmt ->
                stmt.setObject(1, 1)
                stmt.setObject(2, "hello")
                assertTrue(stmt.executeUpdate() > 0)
            }
            conn.initStatement("SELECT name FROM t WHERE id = ?", false, null).use { stmt ->
                stmt.setObject(1, 1)
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertNotNull(rs.getObject(1, String::class))
                }
            }
        }
    }
}
