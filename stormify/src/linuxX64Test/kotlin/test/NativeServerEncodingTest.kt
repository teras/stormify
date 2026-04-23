// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import kotlinx.cinterop.ExperimentalForeignApi
import onl.ycode.kdbc.KdbcDataSource
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.unlink
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Exercises the native drivers against **databases whose server-side encoding
 * is genuinely not UTF-8**. This is the strongest form of the encoding tests
 * in [EncodingTest], and it targets the three dialects where a single process
 * can't put individual columns in different encodings:
 *
 *   - **PostgreSQL** — server_encoding is fixed at database creation. The
 *     test `CREATE DATABASE ... ENCODING 'LATIN1' / 'ISO_8859_7'` at runtime,
 *     opens a dedicated [KdbcDataSource] against each, round-trips the
 *     matching character bucket, then drops the database. Requires the
 *     stormify user to have CREATEDB / superuser (matches docker-compose).
 *
 *   - **SQLite** — encoding is set by `PRAGMA encoding` on a brand-new
 *     database before any table is created. The test creates a fresh file
 *     per encoding (UTF-8, UTF-16le, UTF-16be), runs the pragma, then
 *     round-trips a combined string.
 *
 *   - **Oracle** — the DB character set is fixed at CREATE DATABASE time and
 *     not reconfigurable at runtime. However Oracle exposes a *second*
 *     encoding via the national character set (NCHAR/NVARCHAR2), which our
 *     driver pins to UTF-8 separately via `common.nencoding = "UTF-8"`. This
 *     test exercises that second path via an NVARCHAR2 column; it does NOT
 *     create a new Oracle database (impractical in a test container).
 *
 * Each test is guarded by the active dialect and is a no-op on the others —
 * the harness still instantiates the default DataSource so we can read
 * [Stormify.sqlDialect], but immediately returns if the dialect does not
 * match the test's target.
 *
 * `STORMIFY_TEST_DB=postgresql|sqlite|oracle` selects which test does real
 * work; the others print a skip notice. Running with no env var defaults to
 * SQLite, which exercises the PRAGMA-encoding test.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeServerEncodingTest {

    private val iso88591 = "ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿ¡¢£¤¥§¨©ª«¬®¯°±²³´µ¶·¸¹º»¼½¾¿"
    private val iso88597 = "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩάέήίόύώαβγδεζηθικλμνξοπρστυφχψωςΆΈΉΊΌΎΏ·«»"
    private val utf8Bmp = "日本語中文한국어€₹₽←→↑↓∑∫√∞♠♥♦♣你好世界"
    private val utf16Surrogates = "🌍🚀😀🎉𝕏𝕐𝕑𝟙𝟚𝟛𐌀𐌁𐌂"
    private val allBuckets = "$iso88591|$iso88597|$utf8Bmp|$utf16Surrogates"

    private fun currentDialect(): SqlDialect {
        // Use the default factory just to discover which DB we're pointing at.
        // The data source itself is disposable; we're only reading the dialect.
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

    /**
     * Round-trips per server encoding on PostgreSQL. The target databases
     * (`stormify_enc_latin1`, `stormify_enc_iso7`, `stormify_enc_utf8`) are
     * pre-created by `testing/config/postgresql/init.sql` when the container
     * starts, so the test only needs to open a [KdbcDataSource] against each
     * one and verify that the matching character bucket survives the
     * transcode between the server's storage encoding and the client's UTF-8
     * setting. No CREATE/DROP DATABASE at runtime — that would need
     * superuser and is better handled once at container init.
     *
     * PG has no UTF-16 server encoding, so that bucket is not exercised
     * here (NVARCHAR-style storage simply does not exist on PostgreSQL).
     * UTF8 covers the BMP + supplementary planes on its own.
     */
    @Test
    fun testPostgresPerDatabaseEncoding() {
        if (currentDialect() != SqlDialect.POSTGRESQL) {
            println("  skip: not PostgreSQL")
            return
        }

        // (pg_encoding_name, pre-created database, test string fitting that encoding)
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

            // Drop any leftover from a previous run so the test is idempotent.
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

    /**
     * SQLite lets the application choose the physical storage encoding via
     * `PRAGMA encoding` — but only on a fresh database, before any table has
     * been created. The test creates a brand-new file per encoding, issues
     * the pragma as the first statement, verifies it stuck via a second
     * `PRAGMA encoding` read, and then round-trips a mixed-bucket string.
     *
     * The sqlite3 C library transcodes between the storage encoding and the
     * UTF-8 bytes our C API uses, so a successful round-trip under UTF-16le
     * and UTF-16be proves our driver's text path is not secretly assuming
     * the storage is UTF-8.
     */
    @Test
    fun testSqlitePerFileEncoding() {
        if (currentDialect() != SqlDialect.SQLITE) {
            println("  skip: not SQLite")
            return
        }

        for (encoding in listOf("UTF-8", "UTF-16le", "UTF-16be")) {
            val safeName = encoding.replace("-", "_")
            val path = "/tmp/stormify_enc_${safeName}_${getpid()}.db"
            unlink(path) // start clean — the pragma only sticks on an empty DB

            try {
                val ds = KdbcDataSource("jdbc:sqlite:$path")
                val s = Stormify(ds)
                s.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }

                // The whole PRAGMA → CREATE TABLE → INSERT → SELECT sequence
                // must run on the SAME connection. SQLite's encoding pragma
                // is queued per-connection and only committed to the database
                // file on the first schema change — if we let the pool hand
                // us a fresh connection between PRAGMA and CREATE TABLE, the
                // queued value on the first connection is lost and the new
                // connection starts with the default UTF-8. A Stormify
                // transaction pins a single connection for the whole block,
                // which is exactly what we need here.
                s.transaction {
                    s.executeUpdate("PRAGMA encoding = \"$encoding\"")
                    s.executeUpdate("CREATE TABLE enc_probe (id INT PRIMARY KEY, value TEXT)")
                    // After CREATE TABLE the encoding is committed to the
                    // file header; subsequent PRAGMA encoding reads return
                    // the committed value regardless of which connection
                    // asks, so this check is a reliable sanity gate.
                    val reported = s.readOne<String>("PRAGMA encoding")
                    assertEquals(encoding, reported, "PRAGMA did not stick for $encoding")
                    s.executeUpdate("INSERT INTO enc_probe (id, value) VALUES (?, ?)", 1, allBuckets)
                }

                val back = s.readOne<String>("SELECT value FROM enc_probe WHERE id = ?", 1)
                assertNotNull(back)
                assertStringEqualsCodepointwise(allBuckets, back, "sqlite/$encoding")
            } finally {
                unlink(path)
            }
        }
    }

    // -------------------------------------------------------------- Oracle --

    /**
     * Oracle's database character set (`NLS_CHARACTERSET`) can only be
     * changed via a new database install, which is impractical in a test
     * container. However Oracle exposes a second, independent encoding via
     * the national character set (`NLS_NCHAR_CHARACTERSET`, typically
     * `AL16UTF16`), addressable through NCHAR / NVARCHAR2 / NCLOB columns.
     * Our Oracle driver pins the ODPI-C context's `nencoding` to UTF-8,
     * which makes the server transcode between UTF-16 storage and UTF-8 on
     * the wire. This test exercises that path end-to-end.
     */
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
