package bench

data class DbConfig(
    val key: String,
    val displayName: String,
    val url: String,
    val user: String?,
    val password: String?,
    val dialect: String,
    val driver: String,
)

fun env(name: String, default: String? = null): String? =
    System.getenv(name) ?: default

fun envInt(name: String, default: Int): Int =
    System.getenv(name)?.toIntOrNull() ?: default

fun resolveDb(): DbConfig {
    val key = env("BENCH_DB", "sqlite")!!.lowercase()
    val host = env("BENCH_HOST", "localhost")!!
    return when (key) {
        "sqlite" -> {
            val path = env("BENCH_SQLITE_PATH", "/tmp/stormify_bench.db")!!
            DbConfig("sqlite", "SQLite", "jdbc:sqlite:$path", null, null,
                "org.hibernate.community.dialect.SQLiteDialect", "org.sqlite.JDBC")
        }
        "postgresql", "postgres", "pg" ->
            DbConfig("postgresql", "PostgreSQL 16",
                "jdbc:postgresql://$host:15432/stormify_test", "stormify", "Stormify1!",
                "org.hibernate.dialect.PostgreSQLDialect", "org.postgresql.Driver")
        "mysql" ->
            DbConfig("mysql", "MySQL 8",
                "jdbc:mysql://$host:13306/stormify_test", "stormify", "Stormify1!",
                "org.hibernate.dialect.MySQLDialect", "com.mysql.cj.jdbc.Driver")
        "oracle" ->
            DbConfig("oracle", "Oracle 21c",
                "jdbc:oracle:thin:@$host:11521/XEPDB1", "stormify", "Stormify1!",
                "org.hibernate.dialect.OracleDialect", "oracle.jdbc.OracleDriver")
        "mssql", "sqlserver" ->
            DbConfig("mssql", "MS SQL Server",
                "jdbc:sqlserver://$host:11433;databaseName=stormify_test;encrypt=false;trustServerCertificate=true",
                "sa", "Stormify1!",
                "org.hibernate.dialect.SQLServerDialect", "com.microsoft.sqlserver.jdbc.SQLServerDriver")
        else -> error("Unknown BENCH_DB '$key'")
    }
}

/**
 * The bench has two tables: a small parent (lookup target for the JOIN
 * scenario) and a large child (where every other scenario runs).
 *
 * `parents` ≪ `children`. The bulk-insert scenarios (`insert_1000`,
 * `insert_10000`) populate `bench_child` with the configured row count,
 * clearing between phases; the final phase leaves `children` rows in
 * place for the read scenarios.
 */
class SeedSpec(val parents: Int, val children: Int)
fun seedSpec() = SeedSpec(
    parents = envInt("BENCH_PARENTS", 1000),
    children = envInt("BENCH_CHILDREN", 10000),
)
