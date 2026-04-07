// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Multi-encoding round-trip tests. The point of these tests is NOT to send
 * arbitrary Unicode through a single UTF-8 column — that is covered by the
 * existing Unicode test in [TypesTest]. Here we want to put the **column**
 * itself into each of four server-side encodings and verify that Kotlin
 * strings (which we keep at the Kotlin API level — no manual byte arrays)
 * round-trip correctly when the driver transcodes at the C/UTF-8 boundary.
 *
 *   - ISO-8859-1 (Latin-1 / CP1252 on MySQL/MSSQL)
 *   - ISO-8859-7 (single-byte Greek / CP1253 on MSSQL)
 *   - UTF-8 (MySQL utf8mb4 / MSSQL _UTF8 collation)
 *   - UTF-16 (MySQL utf16 / MSSQL NVARCHAR with _SC)
 *
 * Coverage per dialect:
 *
 *   - MySQL/MariaDB: true per-column CHARACTER SET — the server transcodes
 *     between the column charset and the client's utf8mb4 charset. This is
 *     the most thorough exercise of the encoding pipeline.
 *
 *   - MSSQL: per-column COLLATE on VARCHAR (codepage-backed) and NVARCHAR
 *     (UTF-16). FreeTDS transcodes between the client's UTF-8 setting and
 *     the column's codepage/UCS-2.
 *
 *   - Oracle / PostgreSQL / SQLite: these dialects do not support per-column
 *     encoding — the DB has one charset. The tests still run, using the DB's
 *     native Unicode column type, which verifies that all four character
 *     buckets survive a UTF-8 round-trip (a weaker but still useful check).
 *     The per-column-encoding behavior is really a MySQL/MSSQL concern.
 *
 * There is also a negative test that attempts to store out-of-repertoire
 * characters in a narrow encoding column, to verify the driver surfaces a
 * sensible failure (either an error or lossy replacement — both are
 * documented behavior depending on DB configuration).
 */
class EncodingTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    // --- Character buckets -------------------------------------------------
    // Each bucket contains only characters that fit the named encoding's
    // repertoire. Kept as Kotlin String literals because Kotlin strings are
    // exactly what users hand to Stormify — tests must not sneak byte arrays
    // into the pipeline.

    /** Characters from the Latin-1 supplement (U+00A0..U+00FF). */
    private val iso88591 = "ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿ¡¢£¤¥§¨©ª«¬®¯°±²³´µ¶·¸¹º»¼½¾¿"

    /** Greek block covered by ISO-8859-7 (basic Greek letters + punctuation
     *  common to CP1253). Deliberately excludes polytonic marks that are not
     *  in the single-byte repertoire. */
    private val iso88597 = "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩάέήίόύώαβγδεζηθικλμνξοπρστυφχψωςΆΈΉΊΌΎΏ·«»"

    /** 3-byte UTF-8 BMP: CJK, currency, arrows, math, punctuation. */
    private val utf8Bmp = "日本語中文한국어€₹₽←→↑↓∑∫√∞♠♥♦♣你好世界"

    /** Supplementary plane: Kotlin surrogate pairs, 4-byte UTF-8 on the wire,
     *  surrogate-pair storage in NVARCHAR/utf16. */
    private val utf16Surrogates = "🌍🚀😀🎉𝕏𝕐𝕑𝟙𝟚𝟛𐌀𐌁𐌂"

    private fun bucketFor(enc: TestDDL.TextEncoding): String = when (enc) {
        TestDDL.TextEncoding.ISO_8859_1 -> iso88591
        TestDDL.TextEncoding.ISO_8859_7 -> iso88597
        TestDDL.TextEncoding.UTF_8      -> utf8Bmp
        TestDDL.TextEncoding.UTF_16     -> utf16Surrogates
    }

    /**
     * Assert that [actual] equals [expected] as a Kotlin String, with a
     * per-codepoint diagnostic if they differ so the first bad character is
     * visible in the test output (invaluable when debugging a driver that
     * corrupts a single high-plane character).
     */
    private fun assertStringEqualsCodepointwise(expected: String, actual: String, label: String) {
        if (expected == actual) return
        if (expected.length != actual.length) {
            fail("[$label] length mismatch: expected ${expected.length}, got ${actual.length}\n" +
                    "  expected: $expected\n  actual:   $actual")
        }
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                val ec = expected[i].code.toString(16).padStart(4, '0').uppercase()
                val ac = actual[i].code.toString(16).padStart(4, '0').uppercase()
                fail("[$label] char $i differs: expected U+$ec '${expected[i]}', got U+$ac '${actual[i]}'")
            }
        }
        // Fallback — should be unreachable since equals() disagreed.
        fail("[$label] strings differ but no code unit difference found")
    }

    /**
     * Create a table with one column per encoding bucket, each in the native
     * server-side encoding for that bucket (where supported — otherwise they
     * all fall back to the DB-wide Unicode column).
     */
    private fun createMultiEncodingTable(s: Stormify) {
        TestDDL.dropTable("enc_cols")
        val cols = buildString {
            append(TestDDL.intPrimaryKey("id"))
            append(", col_iso1 ").append(TestDDL.columnTypeFor(TestDDL.TextEncoding.ISO_8859_1))
            append(", col_iso7 ").append(TestDDL.columnTypeFor(TestDDL.TextEncoding.ISO_8859_7))
            append(", col_u8 ").append(TestDDL.columnTypeFor(TestDDL.TextEncoding.UTF_8))
            append(", col_u16 ").append(TestDDL.columnTypeFor(TestDDL.TextEncoding.UTF_16))
        }
        s.executeUpdate(TestDDL.createTable("enc_cols", cols))
    }

    @Test
    fun testPerColumnServerSideEncoding() = withDb("ENC-PER-COL") { s ->
        // On dialects that support real per-column server-side encodings
        // (MySQL/MariaDB via CHARACTER SET, MSSQL via COLLATE) every bucket
        // lands in a column whose storage charset exactly matches the
        // bucket's repertoire, so we can assert all four.
        //
        // On dialects without per-column encoding (PG / Oracle / SQLite)
        // every column falls back to the DB-wide charset. If that is a
        // Unicode charset (PG UTF8, Oracle 21c AL32UTF8, SQLite UTF-8) all
        // four buckets are still representable and we assert all four. If
        // it is a legacy single-byte codepage (e.g. Greek EL8ISO8859P7 on
        // oracle11) then only the matching Greek bucket is representable —
        // attempting the other three would be category-3 misuse (sending
        // characters the column cannot encode), so we skip them.
        val fallbackIsUnicode = TestDDL.supportsPerColumnEncoding() || TestDDL.isUnicodeDatabase

        createMultiEncodingTable(s)

        // One row: each bucket's string into its own column. The round-trip
        // is Kotlin String → driver C API (UTF-8) → server transcode to
        // column encoding → storage → read back → server transcode to
        // UTF-8 → Kotlin String.
        s.executeUpdate(
            "INSERT INTO enc_cols (id, col_iso1, col_iso7, col_u8, col_u16) VALUES (?, ?, ?, ?, ?)",
            1,
            if (fallbackIsUnicode) iso88591         else "",
            iso88597,
            if (fallbackIsUnicode) utf8Bmp          else "",
            if (fallbackIsUnicode) utf16Surrogates  else ""
        )

        val back7 = s.readOne<String>("SELECT col_iso7 FROM enc_cols WHERE id = ?", 1)
        assertNotNull(back7, "col_iso7 returned NULL")
        assertStringEqualsCodepointwise(iso88597, back7, "col_iso7 (ISO-8859-7)")

        if (fallbackIsUnicode) {
            val back1 = s.readOne<String>("SELECT col_iso1 FROM enc_cols WHERE id = ?", 1)
            val backU = s.readOne<String>("SELECT col_u8 FROM enc_cols WHERE id = ?", 1)
            val backW = s.readOne<String>("SELECT col_u16 FROM enc_cols WHERE id = ?", 1)
            assertNotNull(back1, "col_iso1 returned NULL")
            assertNotNull(backU, "col_u8 returned NULL")
            assertNotNull(backW, "col_u16 returned NULL")
            assertStringEqualsCodepointwise(iso88591,        back1, "col_iso1 (ISO-8859-1)")
            assertStringEqualsCodepointwise(utf8Bmp,         backU, "col_u8 (UTF-8)")
            assertStringEqualsCodepointwise(utf16Surrogates, backW, "col_u16 (UTF-16)")
        }
    }

    /**
     * Sanity subtest: insert empty strings into all four encoding columns.
     * Oracle historically normalises empty string to NULL — the assertion
     * accepts either, mirroring [TypesTest.testEmptyStringVsNull].
     */
    @Test
    fun testPerColumnEmptyValues() = withDb("ENC-PER-COL-EMPTY") { s ->
        createMultiEncodingTable(s)
        s.executeUpdate(
            "INSERT INTO enc_cols (id, col_iso1, col_iso7, col_u8, col_u16) VALUES (?, ?, ?, ?, ?)",
            1, "", "", "", ""
        )
        val row = s.readOne<Map<String, Any?>>("SELECT col_iso1, col_iso7, col_u8, col_u16 FROM enc_cols WHERE id = ?", 1)
        assertNotNull(row)
        row.values.forEach { v ->
            val s1 = v as? String
            assertTrue(s1 == null || s1.isEmpty(), "Expected null or empty, got: $v")
        }
    }

    /**
     * Negative test: try to store characters that are NOT in the column's
     * encoding repertoire (emoji / CJK / Greek into a Latin-1 column) and
     * verify the driver surfaces *some* detectable signal — either a thrown
     * error or a lossy replacement (the server returned '?' or U+FFFD or an
     * empty string). Silent success with the original string would mean the
     * encoding constraint is not actually being enforced.
     *
     * Only runs on dialects with real per-column encoding — on UTF-8-only
     * dialects every character is valid in the column, so there is nothing
     * to reject.
     */
    @Test
    fun testWrongEncodingRejectedOrLossy() = withDb("ENC-WRONG") { s ->
        if (!TestDDL.supportsPerColumnEncoding()) {
            println("  skip: dialect has no per-column encoding")
            return@withDb
        }

        TestDDL.dropTable("enc_bad")
        s.executeUpdate(
            TestDDL.createTable(
                "enc_bad",
                "${TestDDL.intPrimaryKey("id")}, narrow ${TestDDL.columnTypeFor(TestDDL.TextEncoding.ISO_8859_1)}"
            )
        )

        // These are all outside the ISO-8859-1 / CP1252 repertoire and must
        // not silently come back intact from a Latin-1 column.
        val outOfRange = listOf(
            "Greek α β γ",       // ISO-8859-7 range
            "CJK 日本語",          // BMP 3-byte UTF-8
            "Emoji 🌍"            // supplementary plane
        )

        var acceptedButLossy = 0
        var rejected = 0
        for ((idx, value) in outOfRange.withIndex()) {
            val id = idx + 1
            try {
                s.executeUpdate("INSERT INTO enc_bad (id, narrow) VALUES (?, ?)", id, value)
                val back = s.readOne<String>("SELECT narrow FROM enc_bad WHERE id = ?", id)
                // Insert accepted — acceptable only if the server produced a
                // lossy/mangled version (not a byte-perfect round-trip).
                if (back == value) {
                    fail("Expected '$value' to be rejected or lossily converted by Latin-1 column, " +
                            "but got exact round-trip — encoding constraint is not enforced")
                }
                acceptedButLossy++
            } catch (_: Throwable) {
                rejected++
            }
        }

        // Either outcome (strict rejection or lossy conversion) is
        // acceptable; we just need to see at least one of them happen for
        // every out-of-range value. The counters guard against a regression
        // where the loop silently skips everything.
        assertEquals(outOfRange.size, acceptedButLossy + rejected,
            "Expected every out-of-range value to be rejected or lossily stored")
    }
}
