// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import kotlinx.cinterop.ExperimentalForeignApi
import onl.ycode.kdbc.KdbcDataSource
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.remove
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Windows version of [NativeServerEncodingTest] — identical logic to the
 * Linux variant, with Windows temp paths and `remove()` instead of `unlink()`.
 *
 * See the Linux version for full documentation of the test strategy.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeServerEncodingTest {

    private val iso88591 = "ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿ¡¢£¤¥§¨©ª«¬®¯°±²³´µ¶·¸¹º»¼½¾¿"
    private val iso88597 = "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩάέήίόύώαβγδεζηθικλμνξοπρστυφχψωςΆΈΉΊΌΎΏ·«»"
    private val utf8Bmp = "日本語中文한국어€₹₽←→↑↓∑∫√∞♠♥♦♣你好世界"
    private val utf16Surrogates = "🌍🚀😀🎉𝕏𝕐𝕑𝟙𝟚𝟛𐌀𐌁𐌂"
    private val allBuckets = "$iso88591|$iso88597|$utf8Bmp|$utf16Surrogates"

    private fun currentDialect(): SqlDialect {
        val testDbs = createTestDatabases()
        assertEquals(1, testDbs.size, "expected a single test database")
        val s = Stormify(testDbs[0].dataSource)
        return s.sqlDialect
    }

    private fun assertStringEqualsCodepointwise(expected: String, actual: String, label: String) {
        if (expected == actual) return
        if (expected.length != actual.length) {
            fail("[$label] length mismatch: expected ${expected.length}, got ${actual.length}")
        }
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                val ec = expected[i].code.toString(16).padStart(4, '0').uppercase()
                val ac = actual[i].code.toString(16).padStart(4, '0').uppercase()
                fail("[$label] char $i differs: expected U+$ec '${expected[i]}', got U+$ac '${actual[i]}'")
            }
        }
        fail("[$label] strings differ but no code unit difference found")
    }

    // ------------------------------------------------------------------ PG --

    @Test
    fun testPostgresPerDatabaseEncoding() {
        if (currentDialect() != SqlDialect.POSTGRESQL) {
            println("  skip: not PostgreSQL")
            return
        }

        val cases = listOf(
            Triple("LATIN1",     "stormify_enc_latin1", iso88591),
            Triple("ISO_8859_7", "stormify_enc_iso7",   iso88597),
            Triple("UTF8",       "stormify_enc_utf8",   "$utf8Bmp $utf16Surrogates")
        )

        val pgPort = when ((getenv("STORMIFY_TEST_DB")?.toKString() ?: "").lowercase()) {
            "postgresql9" -> "15431"
            else -> "15432"
        }

        for ((encoding, dbName, value) in cases) {
            val ds = KdbcDataSource(
                "jdbc:postgresql://localhost:$pgPort/$dbName",
                "stormify",
                "Stormify1!"
            )
            val s = Stormify(ds)
            s.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }

            try { s.executeUpdate("DROP TABLE enc_probe") } catch (_: Throwable) {}
            s.executeUpdate("CREATE TABLE enc_probe (id INT PRIMARY KEY, value TEXT)")
            s.executeUpdate("INSERT INTO enc_probe (id, value) VALUES (?, ?)", 1, value)
            val back = s.readOne<String>("SELECT value FROM enc_probe WHERE id = ?", 1)
            assertNotNull(back, "[$encoding] returned NULL")
            assertStringEqualsCodepointwise(value, back, "pg/$encoding")
            s.executeUpdate("DROP TABLE enc_probe")
        }
    }

    // -------------------------------------------------------------- SQLite --

    @Test
    fun testSqlitePerFileEncoding() {
        if (currentDialect() != SqlDialect.SQLITE) {
            println("  skip: not SQLite")
            return
        }

        val tempDir = getenv("TEMP")?.toKString() ?: getenv("TMP")?.toKString() ?: "."

        for (encoding in listOf("UTF-8", "UTF-16le", "UTF-16be")) {
            val safeName = encoding.replace("-", "_")
            val path = "$tempDir\\stormify_enc_${safeName}_${getpid()}.db"
            remove(path)

            try {
                val ds = KdbcDataSource("jdbc:sqlite:$path")
                val s = Stormify(ds)
                s.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }

                s.transaction {
                    executeUpdate("PRAGMA encoding = \"$encoding\"")
                    executeUpdate("CREATE TABLE enc_probe (id INT PRIMARY KEY, value TEXT)")
                    val reported = readOne<String>("PRAGMA encoding")
                    assertEquals(encoding, reported, "PRAGMA did not stick for $encoding")
                    executeUpdate("INSERT INTO enc_probe (id, value) VALUES (?, ?)", 1, allBuckets)
                }

                val back = s.readOne<String>("SELECT value FROM enc_probe WHERE id = ?", 1)
                assertNotNull(back)
                assertStringEqualsCodepointwise(allBuckets, back, "sqlite/$encoding")
            } finally {
                remove(path)
            }
        }
    }

    // -------------------------------------------------------------- Oracle --

    @Test
    fun testOracleNcharStorage() {
        val dialect = currentDialect()
        if (dialect != SqlDialect.ORACLE_NEW && dialect != SqlDialect.ORACLE_OLD) {
            println("  skip: not Oracle")
            return
        }

        val ds = createTestDatabases()[0].dataSource
        val s = Stormify(ds)
        s.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }

        try { s.executeUpdate("DROP TABLE enc_nchar") } catch (_: Throwable) {}
        s.executeUpdate("CREATE TABLE enc_nchar (id NUMBER(10) PRIMARY KEY, value NVARCHAR2(400))")

        s.executeUpdate("INSERT INTO enc_nchar (id, value) VALUES (?, ?)", 1, iso88591)
        s.executeUpdate("INSERT INTO enc_nchar (id, value) VALUES (?, ?)", 2, iso88597)
        s.executeUpdate("INSERT INTO enc_nchar (id, value) VALUES (?, ?)", 3, utf8Bmp)
        s.executeUpdate("INSERT INTO enc_nchar (id, value) VALUES (?, ?)", 4, utf16Surrogates)

        assertStringEqualsCodepointwise(
            iso88591,
            s.readOne<String>("SELECT value FROM enc_nchar WHERE id = ?", 1)!!,
            "oracle/NCHAR iso-8859-1"
        )
        assertStringEqualsCodepointwise(
            iso88597,
            s.readOne<String>("SELECT value FROM enc_nchar WHERE id = ?", 2)!!,
            "oracle/NCHAR iso-8859-7"
        )
        assertStringEqualsCodepointwise(
            utf8Bmp,
            s.readOne<String>("SELECT value FROM enc_nchar WHERE id = ?", 3)!!,
            "oracle/NCHAR utf-8 BMP"
        )
        assertStringEqualsCodepointwise(
            utf16Surrogates,
            s.readOne<String>("SELECT value FROM enc_nchar WHERE id = ?", 4)!!,
            "oracle/NCHAR utf-16 surrogates"
        )

        s.executeUpdate("DROP TABLE enc_nchar")
    }
}
