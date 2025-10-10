package test

actual fun createTestDatabases(): List<TestDatabase> {
    // Android unit tests run on JVM without Android runtime
    // They cannot access Android-specific classes like android.database.sqlite.SQLiteDatabase
    // For actual Android testing, use instrumented tests (androidTest) or Robolectric
    return emptyList()
}
