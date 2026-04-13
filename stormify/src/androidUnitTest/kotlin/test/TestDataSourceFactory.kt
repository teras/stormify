package test

import android.database.sqlite.SQLiteDatabase
import onl.ycode.kdbc.AndroidDataSource
import java.io.File

actual fun createTestDatabases(): List<TestDatabase> {
    // File-based (not :memory:) so Android's connection pool shares data across
    // connections — in-memory DBs are per-connection on Android SQLite, which
    // breaks tests that DDL outside a transaction and then read via a new connection.
    val file = File.createTempFile("stormify-android-test", ".db").apply { deleteOnExit() }
    file.delete()
    val db = SQLiteDatabase.openOrCreateDatabase(file, null)
    db.execSQL("PRAGMA foreign_keys = ON")
    // WAL allows the Android SQLiteConnectionPool to maintain multiple concurrent
    // connections. Without it the pool is size 1 and the writer/reader connection
    // identity change between execSQL(CREATE) and rawQuery(SELECT) can leave the
    // reader seeing stale schema — tests that DDL then SELECT on a fresh DB fail
    // with "no such table". (Robolectric-observed behaviour; real devices use WAL
    // by default for most apps.)
    db.enableWriteAheadLogging()
    // Android's CursorWindow caps individual row reads at ~2 MB.
    // Close hook releases the pooled connections; otherwise the SQLiteConnectionPool
    // accumulates across test classes and later tests hang in waitForConnection().
    return listOf(TestDatabase(
        name = "android-sqlite",
        dataSource = AndroidDataSource(db),
        maxBlobTestSize = 1_500_000,
        close = { db.close(); file.delete() },
    ))
}
