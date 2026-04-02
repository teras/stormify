package onl.ycode.stormify;

// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

import onl.ycode.stormify.pojos.StressTable;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static onl.ycode.stormify.StormifyManager.stormify;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StressTest extends BaseDbTest {

    @Test
    void testStress() {
        assumeTrue(TestDDL.supportsHighConcurrency(),
                "Skipping stress test for " + stormify().getSqlDialect());

        AtomicInteger countSimple = new AtomicInteger(0);
        AtomicInteger countSelect = new AtomicInteger(0);
        AtomicInteger countInserts = new AtomicInteger(0);
        StormifyManager s = stormify();

        TestDDL.dropTable("stress_table");
        s.executeUpdate(TestDDL.createTable("stress_table",
                TestDDL.intPrimaryKey("id") + ", data " + TestDDL.textType()));
        StressTable model = new StressTable(17, "42");
        model.create();

        String selectExpr = TestDDL.selectExpr("( 1 + 2 ) * 3");
        for (int i = 1; i <= 1000; i++)
            assertEquals(9, s.readOne(Integer.class, selectExpr));

        ExecutorService pool = Executors.newFixedThreadPool(100);
        for (int i = 1; i <= 1000; i++)
            pool.submit(() -> { assertEquals(9, (int) stormify().readOne(Integer.class, selectExpr)); countSimple.incrementAndGet(); });
        for (int i = 1; i <= 1000; i++)
            pool.submit(() -> { assertEquals(model.getData(), stormify().readOne(StressTable.class, "SELECT * FROM stress_table WHERE id = ?", model.getId()).getData()); countSelect.incrementAndGet(); });
        for (int i = 1; i <= 1000; i++)
            pool.submit(() -> { int nextId = countInserts.incrementAndGet(); new StressTable(nextId, "Index #" + nextId).create(); });

        pool.shutdown();
        try {
            if (!pool.awaitTermination(100, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        assertEquals(1000, s.readOne(int.class, "SELECT COUNT(*) FROM stress_table"));

        // Transaction rollback under stress
        try {
            s.transaction(() -> {
                for (int i = 500; i < 600; i++) new StressTable(i).delete();
                assertEquals(900, s.readOne(int.class, "SELECT COUNT(*) FROM stress_table"));
                throw new Exception("Request Rollback");
            });
        } catch (Exception ex) {
            assertEquals("Request Rollback", ex.getCause().getMessage());
        }
        assertEquals(1000, s.readOne(int.class, "SELECT COUNT(*) FROM stress_table"));

        // Nested transaction rollback under concurrency
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            int from = i * 100, upto = (i + 1) * 100;
            threads.add(new Thread(() -> removeAndFail(from, upto)));
        }
        threads.forEach(Thread::start);
        threads.forEach(t -> { try { t.join(); } catch (InterruptedException e) { System.exit(-1); } });

        assertEquals(1000, s.readOne(int.class, "SELECT COUNT(*) FROM stress_table"));
        assertEquals(1000, countSimple.get(), "Simple queries");
        assertEquals(1000, countSelect.get(), "ORM queries");
        assertEquals(1000, countInserts.get(), "Insert queries");
    }

    private static void removeAndFail(int from, int upto) {
        int area = upto - from, p1 = from + area / 4, p2 = from + area / 2, p3 = from + area * 3 / 4;
        try {
            stormify().transaction(() -> {
                for (int i = from; i < p1; i++) new StressTable(i).delete();
                try { stormify().transaction(() -> {
                    for (int i = p1; i < p2; i++) new StressTable(i).delete();
                    try { stormify().transaction(() -> {
                        for (int i = p2; i < p3; i++) new StressTable(i).delete();
                        try { stormify().transaction(() -> {
                            for (int i = p3; i < upto; i++) new StressTable(i).delete();
                            throw new Exception("Rollback 1");
                        }); } catch (Exception ex) { checkError("Rollback 1", ex); }
                        throw new Exception("Rollback 2");
                    }); } catch (Exception ex) { checkError("Rollback 2", ex); }
                    throw new Exception("Rollback 3");
                }); } catch (Exception ex) { checkError("Rollback 3", ex); }
                throw new Exception("Rollback 4");
            });
        } catch (Exception ex) { checkError("Rollback 4", ex); }
    }

    private static void checkError(String message, Throwable th) {
        if (!th.getCause().getMessage().equals(message)) { th.printStackTrace(); System.exit(-1); }
    }
}
