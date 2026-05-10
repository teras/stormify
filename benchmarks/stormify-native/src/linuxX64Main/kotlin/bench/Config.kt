package bench

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
fun env(name: String, default: String? = null): String? =
    getenv(name)?.toKString() ?: default

@OptIn(ExperimentalForeignApi::class)
fun envInt(name: String, default: Int): Int =
    getenv(name)?.toKString()?.toIntOrNull() ?: default

data class DbConfig(
    val key: String,        // sqlite, postgresql, mysql, oracle, mssql
    val displayName: String,
    val url: String,
    val user: String?,
    val password: String?,
)

fun resolveDb(): DbConfig {
    val key = env("BENCH_DB", "sqlite")!!.lowercase()
    val host = env("BENCH_HOST", "localhost")!!
    return when (key) {
        "sqlite" -> {
            val path = env("BENCH_SQLITE_PATH", "/tmp/stormify_bench.db")!!
            DbConfig("sqlite", "SQLite (file)", "jdbc:sqlite:$path", null, null)
        }
        "postgresql", "postgres", "pg" ->
            DbConfig("postgresql", "PostgreSQL 16",
                "jdbc:postgresql://$host:15432/stormify_test", "stormify", "Stormify1!")
        "mysql" ->
            DbConfig("mysql", "MySQL 8",
                "jdbc:mysql://$host:13306/stormify_test", "stormify", "Stormify1!")
        "oracle" ->
            DbConfig("oracle", "Oracle 21c",
                "jdbc:oracle:thin:@$host:11521/XEPDB1", "stormify", "Stormify1!")
        "mssql", "sqlserver" ->
            DbConfig("mssql", "MS SQL Server",
                "jdbc:sqlserver://$host:11433;databaseName=stormify_test", "sa", "Stormify1!")
        else -> error("Unknown BENCH_DB '$key'")
    }
}
