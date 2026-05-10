package bench

import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.Persistence
import org.hibernate.cfg.AvailableSettings

private const val IMPL = "jpa-jvm"

fun buildEmf(cfg: DbConfig): EntityManagerFactory {
    val props = HashMap<String, Any>()
    props[AvailableSettings.JAKARTA_JDBC_URL] = cfg.url
    props[AvailableSettings.JAKARTA_JDBC_DRIVER] = cfg.driver
    if (cfg.user != null) props[AvailableSettings.JAKARTA_JDBC_USER] = cfg.user
    if (cfg.password != null) props[AvailableSettings.JAKARTA_JDBC_PASSWORD] = cfg.password
    props[AvailableSettings.DIALECT] = cfg.dialect
    props[AvailableSettings.HBM2DDL_AUTO] = "none"
    props[AvailableSettings.SHOW_SQL] = "false"
    props[AvailableSettings.STATEMENT_BATCH_SIZE] = "50"
    props[AvailableSettings.ORDER_INSERTS] = "true"
    props[AvailableSettings.ORDER_UPDATES] = "true"
    // Disable second-level cache for fairness with Stormify
    props[AvailableSettings.USE_SECOND_LEVEL_CACHE] = "false"
    props[AvailableSettings.USE_QUERY_CACHE] = "false"
    return Persistence.createEntityManagerFactory("bench", props)
}

fun main(args: Array<String>) {
    val tStart = nowNanos()
    val mode = args.firstOrNull() ?: "bench-read"
    val cfg = resolveDb()
    val csvPath = env("BENCH_CSV", "/tmp/stormify_bench.csv")!!
    val appendCsv = env("BENCH_APPEND", "1") == "1"
    val csv = CsvWriter(csvPath, append = appendCsv)

    when (mode) {
        "prepare" -> {
            val ddl = env("BENCH_DDL", "schema/ddl-${cfg.key}.sql")!!
            applyDdlJdbc(cfg, ddl)
            val tReady = nowNanos()
            csv.row(cfg.key, IMPL, "startup_$mode", "ready_ns", 0, tReady - tStart, 0, rssKb())
            println("prepared ${cfg.key}")
        }
        "bench-insert" -> {
            val emf = buildEmf(cfg)
            val tReady = nowNanos()
            csv.row(cfg.key, IMPL, "startup_$mode", "ready_ns", 0, tReady - tStart, 0, rssKb())
            benchInsert(emf, cfg.key, csv, seedSpec())
            emf.close()
            println("insert done")
        }
        "bench-read" -> {
            val emf = buildEmf(cfg)
            val tReady = nowNanos()
            csv.row(cfg.key, IMPL, "startup_$mode", "ready_ns", 0, tReady - tStart, 0, rssKb())
            benchRead(emf, cfg.key, csv, seedSpec())
            emf.close()
            println("read done")
        }
        else -> error("Unknown mode: $mode")
    }
    csv.row(cfg.key, IMPL, "shutdown_$mode", "total_ns", 0, nowNanos() - tStart, 0, rssKb())
    csv.close()
}
