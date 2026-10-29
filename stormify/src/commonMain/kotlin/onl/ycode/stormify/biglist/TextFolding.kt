// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.stormify.SqlDialect
import kotlin.jvm.JvmField

/**
 * Folds text for case-insensitive (and, if the implementation wants, accent-insensitive)
 * matching of facet filters. The same folding must be applied on both sides of a
 * comparison: [column] wraps the database column in SQL, [value] transforms the text
 * on the client side.
 *
 * Used by:
 * - [Facet.Type.TEXT] filters when the facet is not case-sensitive: the generated
 *   condition is `column(col) likeOperator ?`. The bound pattern is built from the
 *   typed text split at the wildcards: each literal piece goes through [likeValue]
 *   and is then escaped, so a folding never sees, removes or alters a wildcard or an
 *   escape.
 * - [Facet.Type.ENUM] filters, which match display names on the client side by
 *   comparing [value] of the display name with [value] of the typed text.
 *
 * Set it once per [Stormify][onl.ycode.stormify.Stormify] instance through
 * [Stormify.textFolding][onl.ycode.stormify.Stormify.textFolding]. The default,
 * [DEFAULT], is the plain case-insensitive matching of the dialect.
 *
 * An implementation for a specific language typically uppercases the column and
 * removes diacritics with a database function (`TRANSLATE`, `unaccent`, …) in
 * [column], and does the same with the platform's text utilities in [value]. The two
 * must produce the same text for the same input, otherwise filters miss rows.
 *
 * ```kotlin
 * stormify.textFolding = object : TextFolding {
 *     override fun column(columnExpr: String, dialect: SqlDialect) = "UPPER($columnExpr)"
 *     override fun value(text: String) = text.uppercase()
 * }
 * ```
 */
interface TextFolding {
    /** Returns the SQL expression that folds [columnExpr] on the database side. */
    fun column(columnExpr: String, dialect: SqlDialect): String

    /** Folds [text] on the client side, the same way [column] folds the column. */
    fun value(text: String): String

    /**
     * Folds the text bound to a LIKE comparison against [column]. Defaults to [value].
     * Override only when the bound text must differ per dialect from the client-side
     * folding.
     */
    fun likeValue(text: String, dialect: SqlDialect): String = value(text)

    /** The operator that compares the folded column with the bound text. Defaults to `LIKE`. */
    fun likeOperator(dialect: SqlDialect): String = "LIKE"

    /** Holds the [DEFAULT] folding. */
    companion object {
        /**
         * The default folding: `LOWER(column) LIKE ?` with a lowercased value, `ILIKE`
         * on PostgreSQL, and the plain case-insensitive `LIKE` on MySQL, MariaDB and SQL
         * Server, whose collations already ignore case. On the client side the value is
         * lowercased.
         */
        @JvmField
        val DEFAULT: TextFolding = object : TextFolding {
            override fun column(columnExpr: String, dialect: SqlDialect) = dialect.caseInsensitiveLike(columnExpr).first
            override fun value(text: String) = text.lowercase()
            override fun likeValue(text: String, dialect: SqlDialect) = dialect.transformLikeValue(text)
            override fun likeOperator(dialect: SqlDialect) = dialect.caseInsensitiveLike("").second
        }
    }
}
