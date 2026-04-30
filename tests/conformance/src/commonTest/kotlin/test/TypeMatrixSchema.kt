// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import kotlin.test.fail

/**
 * Type-coverage matrix: γράφουμε μία τιμή Kotlin τύπου σε όσες στήλες
 * δηλώνει το test ότι πρέπει να δέχονται τη μετατροπή. Η γραμμή των
 * "expected to work" συμβαδίζει με το standard JDBC reference behavior
 * (π.χ. setObject Number → BOOLEAN ως 0/non-0). Συνδυασμοί πέρα από
 * το JDBC contract (π.χ. String "hello" → INT, Date → BLOB) δεν
 * δοκιμάζονται καθόλου — δεν έχει νόημα να τους υποστηρίξουμε.
 */
enum class ColCat { NUMERIC, BOOLEAN, TEXT, DATE, TIME, TIMESTAMP, BLOB }

object TypeMatrixSchema {
    data class Col(val name: String, val type: String, val cat: ColCat)

    fun columns(): List<Col> {
        val dialect = TestDDL.dialect
        val isOracle = dialect == SqlDialect.ORACLE_NEW || dialect == SqlDialect.ORACLE_OLD
        val isSqlite = dialect == SqlDialect.SQLITE

        val cols = mutableListOf<Col>()
        cols += Col("c_smallint", TestDDL.smallIntType(), ColCat.NUMERIC)
        cols += Col("c_int", TestDDL.intType(), ColCat.NUMERIC)
        cols += Col("c_bigint", TestDDL.bigIntType(), ColCat.NUMERIC)
        cols += Col("c_decimal", TestDDL.decimalType(38, 10), ColCat.NUMERIC)
        cols += Col("c_real", TestDDL.floatType(), ColCat.NUMERIC)
        cols += Col("c_double", TestDDL.doubleType(), ColCat.NUMERIC)
        cols += Col("c_boolean", TestDDL.booleanType(), ColCat.BOOLEAN)
        cols += Col("c_varchar", TestDDL.textType(), ColCat.TEXT)
        cols += Col("c_date", "DATE", ColCat.DATE)
        // Oracle DATE includes time-of-day; ξεχωριστή TIME στήλη δεν υπάρχει
        // ως διακριτός τύπος, οπότε παραλείπεται. SQLite δέχεται οποιοδήποτε
        // type name με affinity rules — TEXT είναι το πιο ασφαλές.
        if (!isOracle) cols += Col("c_time", if (isSqlite) "TEXT" else "TIME", ColCat.TIME)
        cols += Col("c_timestamp", TestDDL.timestampType(), ColCat.TIMESTAMP)
        cols += Col("c_blob", TestDDL.blobType(), ColCat.BLOB)
        return cols
    }

    fun ddl(): String =
        "id ${TestDDL.intType()} PRIMARY KEY, " +
            columns().joinToString(", ") { "${it.name} ${it.type}" }
}

/**
 * Συντομογραφίες για το expected-set στα tests. Boolean ↔ Number δεν
 * περιλαμβάνεται γιατί strict drivers (PostgreSQL) απαιτούν explicit CAST,
 * και η JDBC reference δεν επιβάλλει αυτή τη μετατροπή.
 */
val NUMERIC_LIKE = setOf(ColCat.NUMERIC, ColCat.TEXT)
val BOOLEAN_LIKE = setOf(ColCat.BOOLEAN, ColCat.TEXT)
val TEXT_ONLY = setOf(ColCat.TEXT)
val BLOB_ONLY = setOf(ColCat.BLOB)
val DATE_ONLY = setOf(ColCat.DATE, ColCat.TEXT)
val TIME_ONLY = setOf(ColCat.TIME, ColCat.TEXT)
val TIMESTAMP_ONLY = setOf(ColCat.TIMESTAMP, ColCat.TEXT)
val DATE_AND_TIMESTAMP = setOf(ColCat.DATE, ColCat.TIMESTAMP, ColCat.TEXT)
val DATE_TIME_TIMESTAMP = setOf(ColCat.DATE, ColCat.TIME, ColCat.TIMESTAMP, ColCat.TEXT)
val ALL_CATS = ColCat.values().toSet()

/**
 * Γράφει τη [value] σε κάθε column που ανήκει στις [accept] κατηγορίες
 * (ένα row ανά column για απομόνωση). Αν κάποιος αναμενόμενος συνδυασμός
 * σκάσει, μαζεύει τα errors και καλεί `fail()` με δομημένο μήνυμα.
 */
fun runTypeMatrix(label: String, value: Any?, accept: Set<ColCat>) =
    TestHelper.withDb("MATRIX-$label") { s: Stormify ->
        TestDDL.dropTable("type_matrix")
        s.executeUpdate(TestDDL.createTable("type_matrix", TypeMatrixSchema.ddl()))

        val isMssql = TestDDL.dialect == SqlDialect.SQL_SERVER_NEW ||
            TestDDL.dialect == SqlDialect.SQL_SERVER_OLD
        val cols = TypeMatrixSchema.columns().filter { col ->
            if (col.cat !in accept) return@filter false
            // MSSQL JDBC: untyped null bind to VARBINARY is mapped to NVARCHAR
            // and the server refuses the implicit conversion. Documented
            // limitation with no portable client-side fix.
            if (value == null && col.cat == ColCat.BLOB && isMssql) return@filter false
            true
        }
        val failures = mutableListOf<String>()

        for ((idx, col) in cols.withIndex()) {
            val rowId = idx + 1
            try {
                s.executeUpdate(
                    "INSERT INTO type_matrix (id, ${col.name}) VALUES (?, ?)",
                    rowId, value,
                )
                val n = s.readOne<Int>("SELECT COUNT(*) FROM type_matrix WHERE id = ?", rowId)
                if (n != 1) failures += "${col.name} (${col.type}): row not found after insert"
            } catch (e: Throwable) {
                var pick: Throwable = e
                var cur: Throwable? = e
                while (cur != null) {
                    if (cur.message?.contains("Bind failed") == true) { pick = cur; break }
                    val nxt = cur.cause
                    cur = if (nxt === cur) null else nxt
                }
                val msg = (pick.message ?: "").lineSequence().firstOrNull()?.take(220) ?: ""
                failures += "${col.name} (${col.type}): ${pick::class.simpleName}: $msg"
            }
        }

        if (failures.isNotEmpty()) {
            fail("[$label] failed for ${failures.size}/${cols.size} columns:\n" + failures.joinToString("\n"))
        }
    }
