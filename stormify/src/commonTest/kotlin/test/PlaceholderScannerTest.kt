// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package test

import onl.ycode.stormify.scanPlaceholders
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderScannerTest {

    private fun count(sql: String): Int {
        var n = 0
        scanPlaceholders(sql) { n++ }
        return n
    }

    @Test
    fun bareQuestionMarks() {
        assertEquals(3, count("SELECT * FROM t WHERE a = ? AND b = ? AND c = ?"))
    }

    @Test
    fun ignoresQuestionInsideSingleQuotes() {
        assertEquals(1, count("SELECT * FROM t WHERE name = 'why?' AND id = ?"))
    }

    @Test
    fun handlesEscapedSingleQuoteInLiteral() {
        // 'it''s ?' is one literal containing a literal ' and a ?, then the real placeholder.
        assertEquals(1, count("SELECT 'it''s ?' , ?"))
    }

    @Test
    fun ignoresQuestionInsideDoubleQuotedIdentifier() {
        assertEquals(1, count("""SELECT "weird?col" FROM t WHERE id = ?"""))
    }

    @Test
    fun ignoresQuestionInsideBacktickIdentifier() {
        assertEquals(1, count("SELECT `weird?col` FROM t WHERE id = ?"))
    }

    @Test
    fun ignoresLineComment() {
        val sql = """
            SELECT * FROM t
            -- skip this ? placeholder
            WHERE id = ?
        """.trimIndent()
        assertEquals(1, count(sql))
    }

    @Test
    fun ignoresBlockComment() {
        assertEquals(1, count("SELECT /* a ? in here */ ? FROM t"))
    }

    @Test
    fun unterminatedLiteralConsumesRest() {
        // No exception; remainder is treated as literal so no placeholders.
        assertEquals(0, count("SELECT 'oops ? more"))
    }
}
