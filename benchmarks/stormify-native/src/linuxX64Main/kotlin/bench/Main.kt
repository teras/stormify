package bench

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.generated.GeneratedEntities

private const val IMPL = "stormify-native"

fun main(args: Array<String>) {
    val tStart = nowNanos()
    val mode = args.firstOrNull() ?: "bench-read"
    val cfg = resolveDb()

    val csvPath = env("BENCH_CSV", "/tmp/stormify_bench.csv")!!
    val appendCsv = env("BENCH_APPEND", "1") == "1"
    val csv = CsvWriter(csvPath, append = appendCsv)

    // One persistent connection for the entire run. Mirrors JPA's reuse-via-pool
    // behaviour: zero per-iter connect/auth cost, no pool acquire/release jitter.
    val ds = HoldOpenDataSource(cfg.url, cfg.user, cfg.password)
    val stormify = Stormify(ds, GeneratedEntities).asDefault()
    // Oracle reads `cursorFetchSize` as the row-prefetch hint passed to
    // ODPI-C; a small value forces multiple round-trips per result set
    // (256 default → ~20 RTs for 5000 rows). For the bench's larger
    // scenarios (`paged_scan` 5000 rows, `complex_filter` up to 5000)
    // we bump this to 5000 so Oracle fetches the whole window in one
    // round-trip — same as ojdbc's default behaviour for `resultList`.
    // Other backends ignore this for non-streaming reads.
    stormify.cursorFetchSize = 5000

    val tReady = nowNanos()
    csv.row(cfg.key, IMPL, "startup_$mode", "ready_ns", 0, tReady - tStart, 0, rssKb())

    try {
        when (mode) {
            "prepare" -> {
                val ddl = env("BENCH_DDL", "schema/ddl-${cfg.key}.sql")!!
                applyDdl(stormify, cfg.key, ddl)
                println("prepared ${cfg.key}")
            }
            "bench-insert" -> {
                benchInsert(stormify, cfg.key, csv, seedSpec())
                println("insert done")
            }
            "bench-read" -> {
                benchRead(stormify, cfg.key, csv, seedSpec())
                println("read done")
            }
            else -> error("Unknown mode: $mode")
        }
    } finally {
        ds.close()
    }

    csv.row(cfg.key, IMPL, "shutdown_$mode", "total_ns", 0, nowNanos() - tStart, 0, rssKb())
    csv.close()
}
