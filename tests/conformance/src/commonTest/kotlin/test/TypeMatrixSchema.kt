// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import kotlin.test.fail

/**
 * Φάση 1 type-coverage matrix: το test γράφει μία τιμή Kotlin τύπου σε όλες
 * τις στήλες του πίνακα `type_matrix` και καταγράφει ποιοι (kotlin-type ×
 * db-type) συνδυασμοί σπάνε στο current driver.
 *
 * Σήμερα το stormify/kdbc έχει ασύμμετρα contracts: στο JVM το `setObject`
 * δέχεται μόνο γνωστούς τύπους, στο Native γίνεται `.toString()` fallback.
 * Σκοπός αυτής της matrix είναι να γίνει ορατή η ασυμμετρία ως δεδομένα,
 * πριν αποφασιστεί ο ενοποιημένος registry (Φάση 2).
 */
object TypeMatrixSchema {
    data class Col(val name: String, val type: String)

    fun columns(): List<Col> {
        val dialect = TestDDL.dialect
        val isOracle = dialect == SqlDialect.ORACLE_NEW || dialect == SqlDialect.ORACLE_OLD
        val isSqlite = dialect == SqlDialect.SQLITE

        val cols = mutableListOf<Col>()
        cols += Col("c_smallint", TestDDL.smallIntType())
        cols += Col("c_int", TestDDL.intType())
        cols += Col("c_bigint", TestDDL.bigIntType())
        cols += Col("c_decimal", TestDDL.decimalType(38, 10))
        cols += Col("c_real", TestDDL.floatType())
        cols += Col("c_double", TestDDL.doubleType())
        cols += Col("c_boolean", TestDDL.booleanType())
        cols += Col("c_varchar", TestDDL.textType())
        cols += Col("c_date", "DATE")
        // Oracle DATE includes time-of-day; ξεχωριστή TIME στήλη δεν υπάρχει
        // ως διακριτός τύπος, οπότε παραλείπεται. SQLite δέχεται οποιοδήποτε
        // type name με affinity rules — TEXT είναι το πιο ασφαλές.
        if (!isOracle) cols += Col("c_time", if (isSqlite) "TEXT" else "TIME")
        cols += Col("c_timestamp", TestDDL.timestampType())
        cols += Col("c_blob", TestDDL.blobType())
        return cols
    }

    fun ddl(): String =
        "id ${TestDDL.intType()} PRIMARY KEY, " +
            columns().joinToString(", ") { "${it.name} ${it.type}" }
}

/**
 * Τρέχει την matrix για ένα συγκεκριμένο Kotlin τύπο: γράφει τη μοναδική
 * [value] σε κάθε column ξεχωριστά (ένα row ανά column για απομόνωση), και
 * αν κάποιος συνδυασμός σκάσει, μαζεύει τα errors και καλεί `fail()` με
 * δομημένο μήνυμα — ώστε το JUnit report να δείχνει ξεκάθαρα ποιες (db-type)
 * στήλες απέρριψαν τον τύπο.
 */
fun runTypeMatrix(label: String, value: Any?) = TestHelper.withDb("MATRIX-$label") { s: Stormify ->
    TestDDL.dropTable("type_matrix")
    s.executeUpdate(TestDDL.createTable("type_matrix", TypeMatrixSchema.ddl()))

    val cols = TypeMatrixSchema.columns()
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
            // Stormify's bindParam already prefixes the driver error with
            // "Bind failed for parameter N (type=…, value=…)". Walk the cause
            // chain to find that prefix and prefer it; fall back to the outermost
            // message if the wrap is absent (e.g. errors raised at execute time
            // by drivers that defer type validation past setObject).
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
