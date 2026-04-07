@file:JvmName("StormifyAndroidKt")
package onl.ycode.stormify

import android.database.sqlite.SQLiteDatabase
import onl.ycode.kdbc.AndroidDataSource

/**
 * Creates a Stormify instance from an Android SQLiteDatabase.
 *
 * This is a convenience function for Android that automatically wraps
 * the Android SQLiteDatabase into a KDBC DataSource interface.
 *
 * ## Usage
 * ```kotlin
 * val db = context.openOrCreateDatabase("mydb.db", Context.MODE_PRIVATE, null)
 * val stormify = Stormify(db)
 * ```
 */
@JvmName("fromAndroidSQLiteDatabase")
fun Stormify(db: SQLiteDatabase): Stormify =
    Stormify(AndroidDataSource(db))
