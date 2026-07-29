// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import com.ionspin.kotlin.bignum.decimal.BigDecimal as IonBigDecimal
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.fail

/**
 * Exhaustive read matrix: every stored column crossed with every declared read type.
 *
 * The round-trip type matrix only ever sees values Stormify itself wrote, in the
 * representation it chose. This one covers the opposite direction: a row that is
 * already in the database, read into a type that is not the column's own. That is
 * where each JDBC driver and each native driver applies its own coercion rules, and
 * where JVM and native can drift apart without either side failing.
 *
 * The cross product is generated, not hand-picked, so a combination cannot be missed
 * by omission: a cell with no declared expectation is itself reported as a failure.
 * Every cell is checked in a single run and all disagreements are collected into one
 * report, so the whole fan is visible at once instead of one defect per run.
 *
 * Expectations are derived from the column's logical value by one rule per storage
 * kind rather than listed cell by cell — the rule is the policy, and writing it once
 * is what keeps the matrix honest as columns are added.
 *
 * Values are stored as SQL literals rather than bound parameters so the stored
 * representation is exactly the one under test.
 */
open class ReadMatrixTest {
    @Test
    fun readMatrix() = TestHelper.withDb("READ-MATRIX") { s -> runReadMatrix(s) }
}

// --- Expectation vocabulary ---

private sealed interface Expect

/** The one answer required on every database, on JVM and native alike. */
private class Exactly(val text: String) : Expect

/** The read must fail rather than invent a value. */
private object Refused : Expect

/**
 * No answer is canonical here, and the reason is reported rather than hidden — a
 * silently absent cell would read as "covered" when it is not.
 */
private class NotCanonical(val why: String) : Expect

// --- The declared read types ---

private class ReadAs(val key: String, val read: (Stormify, String) -> Any?)

private val READERS = listOf(
    // The untyped read goes through the row-as-map path, the only API that asks the
    // driver for no particular type. Its expectation is a shape, not a value: the
    // exact class is allowed to differ, the kind of value is not.
    ReadAs("Any") { s, q -> s.read<Map<String, Any>>(q).single().values.first() },
    ReadAs("String") { s, q -> s.readOne<String>(q) },
    ReadAs("Boolean") { s, q -> s.readOne<Boolean>(q) },
    ReadAs("Int") { s, q -> s.readOne<Int>(q) },
    ReadAs("Long") { s, q -> s.readOne<Long>(q) },
    ReadAs("Double") { s, q -> s.readOne<Double>(q) },
    ReadAs("BigDecimal") { s, q -> s.readOne<IonBigDecimal>(q) },
    ReadAs("ByteArray") { s, q -> s.readOne<ByteArray>(q) },
    ReadAs("CharArray") { s, q -> s.readOne<CharArray>(q) },
    ReadAs("Char") { s, q -> s.readOne<Char>(q) },
)

// --- Column model ---

private class Cell(
    val name: String,
    val ddl: String,
    val literal: String,
    val expect: Map<String, Expect>,
)

private fun Cell.overriding(vararg pairs: Pair<String, Expect>) =
    Cell(name, ddl, literal, expect + pairs)

/**
 * The boolean rule, restated here rather than imported: a numeric payload is true
 * when non-zero, anything else is matched against the tokens every driver agrees on.
 * A test that called the implementation would only prove it equals itself.
 */
private fun booleanOf(text: String): Boolean {
    val t = text.trim()
    t.toLongOrNull()?.let { return it != 0L }
    t.toDoubleOrNull()?.let { return it != 0.0 }
    return t.lowercase() in setOf("true", "t", "yes", "y", "1")
}

/**
 * A column holding a number. Integral reads truncate toward zero (JDBC `getInt`),
 * the boolean read follows the non-zero rule, and the textual reads render the same
 * number — compared numerically, so `DECIMAL(18,4)` returning `12.5000` still counts.
 */
private fun numericCell(name: String, ddl: String, literal: String, value: Double, anyShape: String) =
    Cell(
        name, ddl, literal, mapOf(
            "Any" to Exactly(anyShape),
            "String" to Exactly(value.toString()),
            "Boolean" to Exactly((value != 0.0).toString()),
            "Int" to Exactly(value.toLong().toString()),
            "Long" to Exactly(value.toLong().toString()),
            "Double" to Exactly(value.toString()),
            "BigDecimal" to Exactly(value.toString()),
            "CharArray" to Exactly(value.toString()),
            "ByteArray" to NotCanonical("a numeric column has no portable byte view"),
            "Char" to NotCanonical("a number has no canonical single character"),
        )
    )

/**
 * A column holding text. Numeric reads succeed exactly when the text is a number and
 * are refused otherwise — a column of letters must never turn into a value.
 */
private fun textCell(name: String, ddl: String, text: String): Cell {
    val asNumber = text.trim().toDoubleOrNull()
    val integral = if (asNumber == null) Refused else Exactly(asNumber.toLong().toString())
    val fractional = if (asNumber == null) Refused else Exactly(asNumber.toString())
    return Cell(
        name, ddl, "'$text'", mapOf(
            "Any" to Exactly("text"),
            "String" to Exactly(text),
            "CharArray" to Exactly(text),
            "Char" to Exactly(text.first().toString()),
            "Boolean" to Exactly(booleanOf(text).toString()),
            "Int" to integral,
            "Long" to integral,
            "Double" to fractional,
            "BigDecimal" to fractional,
            "ByteArray" to NotCanonical("the bytes of a text column follow its server-side charset"),
        )
    )
}

/**
 * A column holding a true flag. The textual reads are not canonical because the
 * storage genuinely differs: `BOOLEAN` holds a boolean, `BIT` and `NUMBER(1)` hold
 * a 1, and rendering either as text is the driver's business.
 */
private fun booleanCell(name: String, ddl: String, literal: String, anyShape: String): Cell {
    val storage = NotCanonical("boolean storage differs per dialect (BOOLEAN / BIT / NUMBER(1))")
    return Cell(
        name, ddl, literal, mapOf(
            "Any" to Exactly(anyShape),
            "Boolean" to Exactly("true"),
            "Int" to Exactly("1"),
            "Long" to Exactly("1"),
            "Double" to Exactly("1.0"),
            "BigDecimal" to Exactly("1"),
            "String" to storage,
            "CharArray" to storage,
            "Char" to storage,
            "ByteArray" to NotCanonical("a boolean column has no portable byte view"),
        )
    )
}

/** A column holding raw bytes. Everything except the byte reads is refused. */
private fun blobCell(name: String, ddl: String, literal: String, hex: String): Cell {
    val asText = NotCanonical("rendering arbitrary bytes as text is driver-specific")
    return Cell(
        name, ddl, literal, mapOf(
            "Any" to Exactly("bytes"),
            "ByteArray" to Exactly(hex),
            "String" to asText,
            "CharArray" to asText,
            "Char" to asText,
            "Boolean" to Refused,
            "Int" to Refused,
            "Long" to Refused,
            "Double" to Refused,
            "BigDecimal" to Refused,
        )
    )
}

private fun matrixCells(): List<Cell> {
    val d = TestDDL
    // SQLite has no DECIMAL: the portable decimal column is TEXT there, so an
    // untyped read legitimately reports text rather than a number.
    val anyDecimal = if (d.isSqlite) "text" else "numeric"
    // Only the dialects with a real boolean type can report boolean-ness; on the
    // others the column is a small integer (a NUMBER(1) on Oracle) and that is
    // what comes back.
    val anyBoolean = if (d.isPostgres || d.isMssql || d.isMysqlFamily) "bool" else "numeric"
    val blobLiteral = when {
        d.isPostgres -> "'\\x0102030405'::bytea"
        d.isMssql -> "0x0102030405"
        d.dialect == SqlDialect.ORACLE_NEW || d.dialect == SqlDialect.ORACLE_OLD ->
            "TO_BLOB(HEXTORAW('0102030405'))"
        else -> "X'0102030405'"
    }
    val trueLiteral = if (d.isPostgres || d.isMysqlFamily) "TRUE" else "1"

    return listOf(
        // Values are chosen to be exact in a 4-byte float, so no cell can fail on
        // rounding noise instead of on a coercion rule.
        numericCell("n_small", d.smallIntType(), "42", 42.0, "numeric"),
        numericCell("n_int", d.intType(), "42", 42.0, "numeric"),
        numericCell("n_big", d.bigIntType(), "42", 42.0, "numeric"),
        numericCell("n_neg", d.intType(), "-7", -7.0, "numeric"),
        numericCell("n_zero", d.intType(), "0", 0.0, "numeric"),
        numericCell("v_dec", d.decimalType(18, 4), "12.5", 12.5, anyDecimal),
        // Truncation toward zero, not floor: -3.75 must read as -3.
        numericCell("v_dec_neg", d.decimalType(18, 4), "-3.75", -3.75, anyDecimal),
        // Truncation, not rounding: 12.6 must land on the same integer as 12.5.
        numericCell("v_dec_up", d.decimalType(18, 4), "12.6", 12.6, anyDecimal),
        numericCell("f_real", d.floatType(), "1.5", 1.5, "numeric"),
        numericCell("f_dbl", d.doubleType(), "2.5", 2.5, "numeric"),

        booleanCell("b_flag", d.booleanType(), trueLiteral, anyBoolean),

        textCell("t_word", d.textType(), "hello"),
        textCell("t_int", d.textType(), "42"),
        textCell("t_dec", d.textType(), "12.5"),
        textCell("t_zero", d.textType(), "0"),
        textCell("t_neg", d.textType(), "-7"),
        // The Oracle idiom for a flag, since Oracle has no BOOLEAN column type.
        textCell("t_token", d.textType(), "Y"),

        blobCell("x_bin", d.blobType(), blobLiteral, "0102030405"),

        // A large-text column is a CLOB on Oracle, which is where a driver may hand
        // back a live locator instead of the content.
        textCell("t_clob", d.largeTextType(), "hello")
            .overriding("Char" to NotCanonical("a large-text column is a locator on some drivers")),
    )
}

// --- Rendering ---

private val bigNumberClasses = setOf(
    "java.math.BigDecimal", "java.math.BigInteger",
    "com.ionspin.kotlin.bignum.decimal.BigDecimal", "com.ionspin.kotlin.bignum.integer.BigInteger",
)

/**
 * The kind of value an untyped read produced.
 *
 * All numbers collapse into one bucket, because which numeric class comes back is
 * driver territory even among the JDBC drivers alone — the same `INT` column reads
 * as an `Integer` on PostgreSQL and a `BigDecimal` on Oracle, whose single `NUMBER`
 * type carries no integral/decimal distinction. What has to hold everywhere is the
 * category: a number stays a number, text stays text, and bytes stay bytes.
 */
private fun shapeOf(v: Any?): String = when (v) {
    null -> "null"
    is Boolean -> "bool"
    is ByteArray -> "bytes"
    is String, is CharArray, is Char -> "text"
    is Number -> "numeric"
    else -> if (v::class.qualifiedName in bigNumberClasses) "numeric" else "other(${v::class.simpleName})"
}

private fun valueText(v: Any?): String = when (v) {
    null -> "null"
    is ByteArray -> v.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    is CharArray -> v.concatToString()
    else -> v.toString()
}

/**
 * Representation differences collapse: `12.5` and `12.5000` are the same number, and
 * so are `42` and `42.0`. Anything that is not the same number still fails.
 */
private fun cellMatches(expected: String, actual: String): Boolean {
    if (expected == actual) return true
    val e = expected.toDoubleOrNull() ?: return false
    val a = actual.toDoubleOrNull() ?: return false
    return e == a
}

private fun brief(e: Throwable): String {
    var root: Throwable = e
    while (true) {
        val next = root.cause ?: break
        if (next === root) break
        root = next
    }
    return "${root::class.simpleName}: ${(root.message ?: "").lineSequence().firstOrNull()?.take(140)}"
}

// --- The run ---

private fun runReadMatrix(s: Stormify) {
    val cells = matrixCells()
    TestDDL.dropTable("read_matrix")
    s.executeUpdate(
        TestDDL.createTable(
            "read_matrix",
            "${TestDDL.intPrimaryKey("id")}, " + cells.joinToString(", ") { "${it.name} ${it.ddl}" }
        )
    )
    s.executeUpdate(
        "INSERT INTO read_matrix (id, ${cells.joinToString(", ") { it.name }}) " +
            "VALUES (1, ${cells.joinToString(", ") { it.literal }})"
    )

    val failures = mutableListOf<String>()
    val notCanonical = mutableListOf<String>()
    var checked = 0

    for (cell in cells) for (reader in READERS) {
        val where = "${cell.name} (${cell.ddl}) as ${reader.key}"
        when (val expect = cell.expect[reader.key]) {
            null -> failures += "$where: no expectation declared for this cell"
            is NotCanonical -> notCanonical += "$where: ${expect.why}"
            else -> {
                checked++
                val query = "SELECT ${cell.name} FROM read_matrix WHERE id = 1"
                var value: Any? = null
                var error: Throwable? = null
                try {
                    value = reader.read(s, query)
                } catch (e: Throwable) {
                    error = e
                }
                if (expect === Refused) {
                    if (error == null) failures += "$where: expected refusal, got <${valueText(value)}>"
                } else {
                    val want = (expect as Exactly).text
                    if (error != null) failures += "$where: expected <$want>, threw ${brief(error)}"
                    else {
                        val got = if (reader.key == "Any") shapeOf(value) else valueText(value)
                        if (!cellMatches(want, got)) failures += "$where: expected <$want>, got <$got>"
                    }
                }
            }
        }
    }

    println(
        "[read matrix] ${cells.size} columns x ${READERS.size} read types: " +
            "$checked checked, ${notCanonical.size} without a canonical answer"
    )
    if (failures.isNotEmpty()) fail(
        "read matrix: ${failures.size} of $checked cells disagree\n" +
            failures.joinToString("\n") +
            "\n--- not canonical (${notCanonical.size}, checked by nothing) ---\n" +
            notCanonical.joinToString("\n")
    )
}
