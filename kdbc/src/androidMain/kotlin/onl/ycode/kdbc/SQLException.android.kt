// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

import android.database.sqlite.SQLiteAbortException
import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteBindOrColumnIndexOutOfRangeException
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDatatypeMismatchException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteMisuseException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteTableLockedException

/**
 * Android implementation of [extractDriverMetadata]. Walks the cause chain and
 * for each link tries, in order:
 *  - `java.sql.SQLException` — defensive path for the rare case of a JDBC
 *    driver running on Android; returns the driver's `sqlState` / `errorCode`.
 *  - Android `SQLiteException` subclasses — maps the subclass to the matching
 *    SQLite primary error code (1..26). `sqlState` stays `null` because SQLite
 *    does not define SQLSTATE values.
 * The first match wins. Cycles are guarded against by stopping when
 * `cause === self`.
 */
internal actual fun extractDriverMetadata(cause: Throwable?): Pair<String?, Int?> {
    var cur: Throwable? = cause
    while (cur != null) {
        if (cur is java.sql.SQLException) return cur.sqlState to cur.errorCode
        val code = sqliteSubclassCode(cur)
        if (code != null) return null to code
        val nxt = cur.cause
        cur = if (nxt === cur) null else nxt
    }
    return null to null
}

/**
 * Maps an `android.database.sqlite.SQLiteException` subclass to the SQLite
 * primary result code it represents. Returns `null` when [t] is not one of
 * the recognised subclasses (including the bare `SQLiteException` base, where
 * the code is not derivable from the type alone).
 */
private fun sqliteSubclassCode(t: Throwable): Int? = when (t) {
    is SQLiteAccessPermException -> 3                       // SQLITE_PERM
    is SQLiteAbortException -> 4                            // SQLITE_ABORT
    is SQLiteDatabaseLockedException -> 5                   // SQLITE_BUSY
    is SQLiteTableLockedException -> 6                      // SQLITE_LOCKED
    is SQLiteOutOfMemoryException -> 7                      // SQLITE_NOMEM
    is SQLiteReadOnlyDatabaseException -> 8                 // SQLITE_READONLY
    is SQLiteDiskIOException -> 10                          // SQLITE_IOERR
    is SQLiteDatabaseCorruptException -> 11                 // SQLITE_CORRUPT
    is SQLiteFullException -> 13                            // SQLITE_FULL
    is SQLiteCantOpenDatabaseException -> 14                // SQLITE_CANTOPEN
    is SQLiteBlobTooBigException -> 18                      // SQLITE_TOOBIG
    is SQLiteConstraintException -> 19                      // SQLITE_CONSTRAINT
    is SQLiteDatatypeMismatchException -> 20                // SQLITE_MISMATCH
    is SQLiteMisuseException -> 21                          // SQLITE_MISUSE
    is SQLiteBindOrColumnIndexOutOfRangeException -> 25     // SQLITE_RANGE
    else -> null
}
