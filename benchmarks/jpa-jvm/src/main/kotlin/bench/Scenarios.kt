package bench

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import java.sql.Connection
import java.sql.DriverManager

private const val IMPL = "jpa-jvm"

private fun statusFor(idx: Int): String =
    arrayOf("PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED")[idx % 5]

fun applyDdlJdbc(cfg: DbConfig, ddlPath: String) {
    val ddl = java.io.File(ddlPath).readText()
    val conn: Connection = if (cfg.user == null) DriverManager.getConnection(cfg.url)
    else DriverManager.getConnection(cfg.url, cfg.user, cfg.password)
    conn.use { c ->
        c.autoCommit = true
        val stmts = if (cfg.key == "oracle")
            ddl.split(Regex("\n/\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        else
            ddl.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        c.createStatement().use { st ->
            for (s in stmts) {
                try { st.execute(s) } catch (e: Throwable) {
                    if (s.uppercase().contains("DROP")) continue else throw e
                }
            }
        }
    }
}

private fun deleteChildren(em: EntityManager) {
    em.transaction.begin()
    em.createQuery("DELETE FROM BenchChild").executeUpdate()
    em.transaction.commit()
}

private fun insertChildren(emf: EntityManagerFactory, n: Int, parents: Int) {
    val em = emf.createEntityManager()
    val batchSize = 50
    em.transaction.begin()
    for (i in 1..n) {
        val c = BenchChild().apply {
            id = i
            parent = em.getReference(BenchParent::class.java, ((i - 1) % parents) + 1)
            status = statusFor(i)
            value = (i % 1000) * 1.5
            payload = "payload-$i"
            createdAt = 1700000000000L + i.toLong()
        }
        em.persist(c)
        if (i % batchSize == 0) { em.flush(); em.clear() }
    }
    em.transaction.commit()
    em.close()
}

fun benchInsert(emf: EntityManagerFactory, db: String, csv: CsvWriter, spec: SeedSpec) {
    val batchSize = 50

    // Wipe both tables first so re-running bench-insert without re-prepare
    // is idempotent (FK order: children before parents).
    run {
        val em = emf.createEntityManager()
        deleteChildren(em)
        em.transaction.begin()
        em.createQuery("DELETE FROM BenchParent").executeUpdate()
        em.transaction.commit()
        em.close()
    }

    // Parent rows go in once and stay — they're the lookup target for the JOIN
    // and N+1 scenarios. NOT measured (this is part of seeding, not of the
    // bench itself).
    run {
        val em = emf.createEntityManager()
        em.transaction.begin()
        for (i in 1..spec.parents) {
            em.persist(BenchParent().apply {
                id = i; name = "parent_$i"; createdAt = 1700000000000L + i.toLong()
            })
            if (i % batchSize == 0) { em.flush(); em.clear() }
        }
        em.transaction.commit()
        em.close()
    }

    // 1. insert_1000 — populate bench_child with 1000 rows.
    run {
        val em = emf.createEntityManager()
        deleteChildren(em); em.close()
        val t = nowNanos()
        insertChildren(emf, 1000, spec.parents)
        csv.row(db, IMPL, "insert_1000", "total", 0, nowNanos() - t, 1000, rssKb())
    }

    // 2. insert_10000 — clear, refill with the full data set the read
    // scenarios run against. Leaves bench_child populated for benchRead.
    run {
        val em = emf.createEntityManager()
        deleteChildren(em); em.close()
        val t = nowNanos()
        insertChildren(emf, spec.children, spec.parents)
        csv.row(db, IMPL, "insert_10000", "total", 0, nowNanos() - t, spec.children, rssKb())
    }

    // 3. single_insert_1000 — 1000 separate single-row transactions
    // (per-iter cost dominates on InnoDB / WAL-fsync drivers).
    val singleN = 1000
    val baseId = spec.children + 1
    val tt = nowNanos()
    repeat(singleN) { i ->
        val s = nowNanos()
        val em = emf.createEntityManager()
        em.transaction.begin()
        em.persist(BenchChild().apply {
            id = baseId + i
            parent = em.getReference(BenchParent::class.java, ((i % spec.parents) + 1))
            status = "PAID"
            value = i * 1.0
            payload = "single-$i"
            createdAt = 1700000000000L
        })
        em.transaction.commit()
        em.close()
        csv.row(db, IMPL, "single_insert_1000", "iter", i, nowNanos() - s, 1, -1)
    }
    csv.row(db, IMPL, "single_insert_1000", "total", 0, nowNanos() - tt, singleN, rssKb())
}

/** JIT warmup against the dedicated `bench_warmup` table — the data tables stay virgin. */
private fun warmupJit(emf: EntityManagerFactory) {
    val em = emf.createEntityManager()
    repeat(30) {
        try {
            em.clear()
            em.find(BenchWarmup::class.java, (1..10).random())
            em.createQuery("SELECT w FROM BenchWarmup w", BenchWarmup::class.java).resultList
            em.createQuery("SELECT COUNT(w) FROM BenchWarmup w", java.lang.Long::class.java).singleResult
            em.transaction.begin()
            em.createQuery("UPDATE BenchWarmup w SET w.payload = :n WHERE w.id = :id")
                .setParameter("n", "x").setParameter("id", 1).executeUpdate()
            em.transaction.commit()
        } catch (_: Throwable) { if (em.transaction.isActive) em.transaction.rollback() }
    }
    em.close()
}

fun benchRead(emf: EntityManagerFactory, db: String, csv: CsvWriter, spec: SeedSpec) {
    val measure = envInt("BENCH_ITERS", 500)
    val window = 1000

    warmupJit(emf)

    val em = emf.createEntityManager()

    fun winStart(i: Int): Int {
        val span = (spec.children - window).coerceAtLeast(1)
        return ((i * 17) % span) + 1
    }

    val scenarioFilter: Set<String>? = env("BENCH_SCENARIOS")
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        ?.takeIf { it.isNotEmpty() }

    // Unmeasured warm-up iterations. The point is to let the DATABASE
    // reach the steady state any real production workload would already
    // be in (buffer pool primed, plan cached, prepared statement handle
    // ready, JIT settled). Same SQL, different parameter window per
    // iter, so no result-set data is cached at the ORM level. Override
    // with BENCH_WARM (default 5).
    val scenarioWarmup = envInt("BENCH_WARM", 5)
    fun runScenario(name: String, iters: Int, body: (Int) -> Int) {
        if (scenarioFilter != null && name !in scenarioFilter) return
        repeat(scenarioWarmup) { idx ->
            try { body(idx) } catch (_: Throwable) { /* mirror outer error path */ }
        }
        try {
            for (i in 0 until iters) {
                val t = nowNanos()
                val rows = body(i)
                val d = nowNanos() - t
                csv.row(db, IMPL, name, "iter", i, d, rows, -1)
            }
        } catch (e: Throwable) {
            System.err.println("  scenario '$name' on $db failed: ${e.message}")
            try { if (em.transaction.isActive) em.transaction.rollback() } catch (_: Throwable) {}
        }
    }

    runScenario("findById", measure) { _ ->
        val id = (1..spec.children).random()
        em.clear()
        val c = em.find(BenchChild::class.java, id)
        if (c == null) -1 else 1
    }

    runScenario("list_window_1000", measure) { i ->
        em.clear()
        val s = winStart(i)
        val list = em.createQuery(
            "SELECT c FROM BenchChild c WHERE c.id BETWEEN :a AND :b", BenchChild::class.java
        ).setParameter("a", s).setParameter("b", s + window - 1).resultList
        list.size
    }

    runScenario("join_child_parent", measure) { i ->
        em.clear()
        val s = winStart(i)
        val list = em.createQuery(
            "SELECT NEW bench.ChildParentDto(c.id, c.value, p.name) FROM BenchChild c JOIN c.parent p WHERE c.id BETWEEN :a AND :b",
            ChildParentDto::class.java
        ).setParameter("a", s).setParameter("b", s + 499).resultList
        list.size
    }

    // N+1 architectural test — load N children, then resolve their referenced
    // parents. JPA's @ManyToOne lazy proxy makes this fire one SELECT per row.
    fun runNPlusOne(name: String, rangeSize: Int, iters: Int) {
        runScenario(name, iters) { i ->
            em.clear()
            val s = winStart(i)
            val children = em.createQuery(
                "SELECT c FROM BenchChild c WHERE c.id BETWEEN :a AND :b", BenchChild::class.java
            ).setParameter("a", s).setParameter("b", s + rangeSize - 1).resultList
            var c = 0
            for (ch in children) {
                val n = ch.parent.name  // lazy → one SELECT per child
                if (n.isNotEmpty()) c++
            }
            c
        }
    }
    runNPlusOne("n_plus_1_50",   50,   measure)
    runNPlusOne("n_plus_1_1000", 1000, measure)

    runScenario("paged_scan", (measure / 5).coerceAtLeast(5)) { i ->
        em.clear()
        val s = winStart(i)
        var c = 0
        em.createQuery("SELECT c FROM BenchChild c WHERE c.id BETWEEN :a AND :b", BenchChild::class.java)
            .setParameter("a", s).setParameter("b", s + 4999)
            .setHint("org.hibernate.fetchSize", 50)
            .resultStream.use { st -> st.forEach { c++; em.detach(it) } }
        c
    }

    val statuses = arrayOf("PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED")
    runScenario("complex_filter", measure) { i ->
        em.clear()
        val s = winStart(i)
        val list = em.createQuery(
            "SELECT c FROM BenchChild c WHERE c.status = :s AND c.value BETWEEN :lo AND :hi AND c.id BETWEEN :a AND :b",
            BenchChild::class.java
        ).setParameter("s", statuses[i % statuses.size])
            .setParameter("lo", 100.0).setParameter("hi", 800.0)
            .setParameter("a", s).setParameter("b", s + 4999).resultList
        list.size
    }

    runScenario("update_bulk_sql", (measure / 10).coerceAtLeast(5)) { i ->
        val s = winStart(i)
        em.transaction.begin()
        val n = em.createQuery("UPDATE BenchChild c SET c.status = :s WHERE c.id BETWEEN :a AND :b")
            .setParameter("s", "SHIPPED").setParameter("a", s).setParameter("b", s + 99)
            .executeUpdate()
        em.transaction.commit()
        n
    }

    runScenario("update_bulk_orm", (measure / 10).coerceAtLeast(5)) { i ->
        em.clear()
        em.transaction.begin()
        val s = winStart(i)
        val list = em.createQuery(
            "SELECT c FROM BenchChild c WHERE c.id BETWEEN :a AND :b", BenchChild::class.java
        ).setParameter("a", s).setParameter("b", s + 99).resultList
        for (c in list) c.status = "PROCESSED"
        em.transaction.commit()
        list.size
    }

    runScenario("tx_rollback", (measure / 10).coerceAtLeast(5)) { i ->
        val id = (i % spec.children) + 1
        try {
            em.transaction.begin()
            em.createQuery("UPDATE BenchChild c SET c.status = :s WHERE c.id = :id")
                .setParameter("s", "X").setParameter("id", id).executeUpdate()
            em.transaction.rollback()
        } catch (_: Throwable) { if (em.transaction.isActive) em.transaction.rollback() }
        1
    }

    em.close()
}
