// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies that a boolean stored as text reads back identically on **every**
 * supported database and on both JVM and native.
 *
 * Databases without a native BOOLEAN column type — Oracle above all — encode
 * flags as `CHAR(1)`/`VARCHAR` holding `'Y'`, `'T'` or `'1'`. JDBC drivers accept
 * that token set in `getBoolean`, so the native driver and the shared conversion
 * layer must accept it too, otherwise the same schema yields opposite results
 * depending on the target platform.
 *
 * The tokens exercised here are the ones every driver agrees on. `'on'`/`'off'`
 * are deliberately excluded: only pgjdbc recognises them.
 */
open class BooleanTokenTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    private val trueTokens = listOf("true", "TRUE", "t", "T", "yes", "YES", "y", "Y", "1")
    private val falseTokens = listOf("false", "FALSE", "f", "no", "n", "0")

    @Test
    fun testTextFlagReadsAsBoolean() {
        withDb("BOOLEAN-TEXT-TOKENS") { s ->
            TestDDL.dropTable("kdbc_bool_tok")
            s.executeUpdate(
                TestDDL.createTable(
                    "kdbc_bool_tok",
                    "${TestDDL.intPrimaryKey("id")}, flag ${TestDDL.textType()}"
                )
            )
            try {
                val tokens = trueTokens + falseTokens
                tokens.forEachIndexed { i, token ->
                    s.executeUpdate("INSERT INTO kdbc_bool_tok (id, flag) VALUES (?, ?)", i, token)
                }
                tokens.forEachIndexed { i, token ->
                    val read = s.readOne<Boolean>("SELECT flag FROM kdbc_bool_tok WHERE id = ?", i)
                    assertNotNull(read, "token '$token' read back as null")
                    assertEquals(token in trueTokens, read, "token '$token'")
                }
            } finally {
                TestDDL.dropTable("kdbc_bool_tok")
            }
        }
    }

    /** The numeric encoding must keep working: any non-zero is true, zero is false. */
    @Test
    fun testNumericFlagReadsAsBoolean() {
        withDb("BOOLEAN-NUMERIC") { s ->
            TestDDL.dropTable("kdbc_bool_num")
            s.executeUpdate(
                TestDDL.createTable(
                    "kdbc_bool_num",
                    "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("flag")}"
                )
            )
            try {
                listOf(0, 1, 2, -1).forEachIndexed { i, n ->
                    s.executeUpdate("INSERT INTO kdbc_bool_num (id, flag) VALUES (?, ?)", i, n)
                }
                listOf(0, 1, 2, -1).forEachIndexed { i, n ->
                    val read = s.readOne<Boolean>("SELECT flag FROM kdbc_bool_num WHERE id = ?", i)
                    assertNotNull(read, "value $n read back as null")
                    assertEquals(n != 0, read, "value $n")
                }
            } finally {
                TestDDL.dropTable("kdbc_bool_num")
            }
        }
    }
}
