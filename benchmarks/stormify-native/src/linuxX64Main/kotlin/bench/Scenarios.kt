package bench

import kotlinx.cinterop.ExperimentalForeignApi
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.findById

private fun statusFor(idx: Int): String =
    arrayOf("PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED")[idx % 5]

/**
 * Two-table seed: a small `bench_parent` (lookup target for the JOIN) plus a
 * large `bench_child` (where every other scenario runs). Bulk-insert phases
 * populate `bench_child` with the configured row count, clearing the table
 * between phases; the final phase leaves `children` rows in place for the
 * read scenarios.
 */
class SeedSpec(val parents: Int, val children: Int)

fun seedSpec() = SeedSpec(
    parents = envInt("BENCH_PARENTS", 1000),
    children = envInt("BENCH_CHILDREN", 10000),
)

/** Apply DDL via raw SQL (split on ";" or "/" for Oracle). */
fun applyDdl(stormify: Stormify, dbKey: String, ddlPath: String) {
    val ddl = readFileText(ddlPath)
    val stmts = if (dbKey == "oracle") ddl.split("\n/\n", "\n/\r\n").map { it.trim() }.filter { it.isNotEmpty() }
    else ddl.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    for (s in stmts) {
        try { stormify.executeUpdate(s) } catch (e: Throwable) {
            if (s.uppercase().contains("DROP")) continue
            throw e
        }
    }
}

private fun makeChild(id: Int, parents: Int) = BenchChild(
    id = id,
    parentId = ((id - 1) % parents) + 1,
    status = statusFor(id),
    value = (id % 1000) * 1.5,
    payload = "payload-$id",
    createdAt = 1700000000000L + id.toLong(),
)

private fun insertChildren(stormify: Stormify, n: Int, parents: Int) {
    stormify.transaction {
        var batch = ArrayList<BenchChild>(5000)
        for (i in 1..n) {
            batch.add(makeChild(i, parents))
            if (batch.size >= 5000) {
                stormify.create(batch)
                batch = ArrayList(5000)
            }
        }
        if (batch.isNotEmpty()) stormify.create(batch)
    }
}

fun benchInsert(stormify: Stormify, db: String, csv: CsvWriter, spec: SeedSpec) {
    warmupJit(stormify)

    // Wipe both tables first so re-running bench-insert without re-prepare
    // is idempotent. Children must go before parents to satisfy the FK.
    stormify.transaction {
        stormify.executeUpdate("DELETE FROM bench_child")
        stormify.executeUpdate("DELETE FROM bench_parent")
    }

    // Parents go in once and stay — they back the JOIN and N+1 lookups.
    val parents = (1..spec.parents).map {
        BenchParent(id = it, name = "parent_$it",
            createdAt = 1700000000000L + it.toLong())
    }
    stormify.transaction { stormify.create(parents) }

    // 1. insert_1000 — clear bench_child, refill with 1000 rows.
    stormify.transaction { stormify.executeUpdate("DELETE FROM bench_child") }
    val t1 = nowNanos()
    insertChildren(stormify, 1000, spec.parents)
    csv.row(db, "stormify-native", "insert_1000", "total", 0, nowNanos() - t1, 1000, rssKb())

    // 2. insert_10000 — clear, refill with the full data set the read
    // scenarios run against. Leaves bench_child populated for benchRead.
    stormify.transaction { stormify.executeUpdate("DELETE FROM bench_child") }
    val t2 = nowNanos()
    insertChildren(stormify, spec.children, spec.parents)
    csv.row(db, "stormify-native", "insert_10000", "total", 0, nowNanos() - t2, spec.children, rssKb())

    // 3. single_insert_1000 — 1000 separate single-row transactions.
    val singleN = 1000
    val baseId = spec.children + 1
    val t4 = nowNanos()
    repeat(singleN) { i ->
        val c = BenchChild(id = baseId + i, parentId = (i % spec.parents) + 1,
            status = "PAID", value = i * 1.0, payload = "single-$i",
            createdAt = 1700000000000L)
        val s = nowNanos()
        stormify.transaction { stormify.create(c) }
        val d = nowNanos() - s
        csv.row(db, "stormify-native", "single_insert_1000", "iter", i, d, 1, -1)
    }
    csv.row(db, "stormify-native", "single_insert_1000", "total", 0, nowNanos() - t4, singleN, rssKb())
}

/**
 * JIT-only warmup against the dedicated `bench_warmup` table — the data
 * tables stay virgin. Reads run without transaction wrapping; the lone
 * UPDATE is wrapped in a transaction (matching JPA's warmup).
 */
private fun warmupJit(stormify: Stormify) {
    repeat(30) {
        try {
            stormify.read<BenchWarmup>("SELECT * FROM bench_warmup")
            stormify.findById<BenchWarmup>(((1..10).random()))
            stormify.transaction {
                stormify.executeUpdate("UPDATE bench_warmup SET payload = ? WHERE id = ?", "x", 1)
            }
            stormify.read<Map<String, Any?>>("SELECT id, payload FROM bench_warmup")
            stormify.readOne<Long>("SELECT COUNT(*) FROM bench_warmup")
            stormify.readCursor<BenchWarmup>("SELECT * FROM bench_warmup") { _ -> }
        } catch (_: Throwable) { /* tolerate per-DB quirks */ }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun benchRead(stormify: Stormify, db: String, csv: CsvWriter, spec: SeedSpec) {
    val measure = envInt("BENCH_ITERS", 500)
    warmupJit(stormify)
    try {
        runReads(stormify, db, csv, spec, measure)
    } catch (e: Throwable) {
        platform.posix.fprintf(platform.posix.stderr,
            "  bench-read on %s ended with: %s\n", db, e.message ?: "?")
    }
}

private fun runReads(stormify: Stormify, db: String, csv: CsvWriter, spec: SeedSpec, measure: Int) {
    val window = 1000
    fun winStart(i: Int): Int {
        val span = (spec.children - window).coerceAtLeast(1)
        return ((i * 17) % span) + 1
    }

    runScenario(csv, db, "findById", measure) { _ ->
        val id = (1..spec.children).random()
        val c = stormify.findById<BenchChild>(id)
        if (c == null) -1 else 1
    }

    runScenario(csv, db, "list_window_1000", measure) { i ->
        val s = winStart(i)
        stormify.read<BenchChild>(
            "SELECT * FROM bench_child WHERE id BETWEEN ? AND ?", s, s + window - 1
        ).size
    }

    runScenario(csv, db, "join_child_parent", measure) { i ->
        val s = winStart(i)
        stormify.read<ChildParentDto>(
            "SELECT c.id AS cid, c.value, p.name FROM bench_child c JOIN bench_parent p ON c.parent_id = p.id WHERE c.id BETWEEN ? AND ?",
            s, s + 499
        ).size
    }

    fun runNPlusOne(name: String, rangeSize: Int, iters: Int) {
        runScenario(csv, db, name, iters) { i ->
            val s = winStart(i)
            val children = stormify.read<BenchChild>(
                "SELECT * FROM bench_child WHERE id BETWEEN ? AND ?", s, s + rangeSize - 1
            )
            val ids = children.map { it.parentId }.toSet().toList()
            if (ids.isEmpty()) return@runScenario 0
            val placeholders = ids.joinToString(",") { "?" }
            stormify.read<BenchParent>(
                "SELECT * FROM bench_parent WHERE id IN ($placeholders)",
                *ids.toTypedArray<Any?>()
            ).size
        }
    }
    runNPlusOne("n_plus_1_50",   50,   measure)
    runNPlusOne("n_plus_1_1000", 1000, measure)

    runScenario(csv, db, "paged_scan", (measure / 5).coerceAtLeast(5)) { i ->
        val s = winStart(i)
        var c = 0
        stormify.readCursor<BenchChild>(
            "SELECT * FROM bench_child WHERE id BETWEEN ? AND ?", s, s + 4999
        ) { c++ }
        c
    }

    val statuses = arrayOf("PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED")
    runScenario(csv, db, "complex_filter", measure) { i ->
        val st = statuses[i % statuses.size]
        val s = winStart(i)
        stormify.read<BenchChild>(
            "SELECT * FROM bench_child WHERE status = ? AND value BETWEEN ? AND ? AND id BETWEEN ? AND ?",
            st, 100.0, 800.0, s, s + 4999
        ).size
    }

    // Writes — wrapped in transactions (matching JPA's em.transaction.begin/commit).
    runScenario(csv, db, "update_bulk_sql", (measure / 10).coerceAtLeast(5)) { i ->
        stormify.transaction {
            val s = winStart(i)
            stormify.executeUpdate(
                "UPDATE bench_child SET status = ? WHERE id BETWEEN ? AND ?",
                "SHIPPED", s, s + 99
            )
        }
    }

    runScenario(csv, db, "update_bulk_orm", (measure / 10).coerceAtLeast(5)) { i ->
        stormify.transaction {
            val s = winStart(i)
            val list = stormify.read<BenchChild>(
                "SELECT * FROM bench_child WHERE id BETWEEN ? AND ?", s, s + 99
            )
            list.forEach { it.status = "PROCESSED" }
            stormify.update(list)
            list.size
        }
    }

    runScenario(csv, db, "tx_rollback", (measure / 10).coerceAtLeast(5)) { i ->
        val id = ((i % spec.children) + 1)
        try {
            stormify.transaction {
                stormify.executeUpdate("UPDATE bench_child SET status = ? WHERE id = ?", "X", id)
                throw RuntimeException("rollback")
            }
        } catch (_: RuntimeException) {}
        1
    }
}

private val scenarioFilter: Set<String>? by lazy {
    env("BENCH_SCENARIOS")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        ?.takeIf { it.isNotEmpty() }
}

/**
 * Number of unmeasured warm-up iterations to run before each scenario's
 * timed loop. The point of these is to let the **database** reach the
 * steady state any real production workload would already be in — buffer
 * pool primed, query plan cached, prepared-statement handle on the
 * server side, JIT/AOT settled. They run with the **same SQL** as the
 * measured iterations but with a different parameter window each time,
 * so no result-set data is cached on the ORM side. This warms the DB,
 * not the workload. Override with `BENCH_WARM`.
 */
private val scenarioWarmup: Int by lazy { envInt("BENCH_WARM", 5) }

@OptIn(ExperimentalForeignApi::class)
private fun runScenario(csv: CsvWriter, db: String, name: String, measure: Int, body: (Int) -> Int) {
    if (scenarioFilter != null && name !in scenarioFilter!!) return
    // Discarded warm-up — same SQL, different params per iter.
    repeat(scenarioWarmup) { idx ->
        try { body(idx) } catch (_: Throwable) { /* mirror outer error path */ }
    }
    try {
        for (i in 0 until measure) {
            val t = nowNanos()
            val rows = body(i)
            val d = nowNanos() - t
            csv.row(db, "stormify-native", name, "iter", i, d, rows, -1)
        }
    } catch (e: Throwable) {
        platform.posix.fprintf(platform.posix.stderr,
            "  scenario '%s' on %s failed: %s\n", name, db, e.message ?: "?")
    }
}
