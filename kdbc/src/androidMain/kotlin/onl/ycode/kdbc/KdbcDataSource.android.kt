// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

/**
 * Android actual for [KdbcDataSource]. Not yet implemented.
 *
 * On Android, use [AndroidDataSource] directly to wrap an `android.database.sqlite.SQLiteDatabase`.
 * Network databases are out of scope for Android native builds.
 */
actual fun KdbcDataSource(
    url: String,
    user: String?,
    password: String?
): DataSource {
    throw SQLException(
        "KdbcDataSource(url) is not implemented on Android. " +
                "Use AndroidDataSource(SQLiteDatabase) to wrap a platform SQLite database instead."
    )
}
