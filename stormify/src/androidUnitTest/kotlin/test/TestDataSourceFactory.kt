package test

import android.database.sqlite.SQLiteDatabase
import onl.ycode.kdbc.AndroidDataSource

actual fun createTestDatabases(): List<TestDatabase> {
    val db = SQLiteDatabase.create(null) // in-memory SQLite via Robolectric
    db.execSQL("PRAGMA foreign_keys = ON")
    // Android's CursorWindow caps individual row reads at ~2 MB.
    return listOf(TestDatabase("android-sqlite", AndroidDataSource(db), maxBlobTestSize = 1_500_000))
}
