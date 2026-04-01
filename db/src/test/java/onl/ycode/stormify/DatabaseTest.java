package onl.ycode.stormify;

// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

import ch.qos.logback.classic.Level;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import onl.ycode.stormify.pojos.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static onl.ycode.stormify.StormifyManager.stormify;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseTest {

    private static final TestLogger logger = new TestLogger();
    private static boolean dbAvailable = false;

    @BeforeAll
    public static void initDatabase() {
        String configPath = System.getProperty("stormify.test.config");
        if (configPath == null || configPath.isEmpty()) {
            // Default to SQLite in-memory
            configPath = null;
        }
        try {
            HikariConfig config = configPath != null ? new HikariConfig(configPath) : new HikariConfig();
            if (configPath == null) {
                config.setJdbcUrl("jdbc:sqlite::memory:");
            }
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.zaxxer.hikari")).setLevel(Level.ERROR);
            StormifyManager.stormify().setDataSource(new HikariDataSource(config));
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("********** Database not available **********");
            return;
        }
        try (Connection connection = StormifyManager.stormify().getDataSource().getConnection()) {
            if (!connection.isValid(10))
                throw new IllegalStateException("Database connection not available");
        } catch (Exception e) {
            throw new IllegalStateException("Database connection not available", e);
        }
        stormify().registerPrimaryKeyResolver(0, (a, c) -> c.toLowerCase().startsWith("id"));
        dbAvailable = true;
        System.out.println("Connected to database, dialect: " + stormify().getSqlDialect());
    }

    @BeforeEach
    public void checkDb() {
        assumeTrue(dbAvailable, "Database not available");
        logger.get(); // clear any pending log entries
    }

    @AfterAll
    public static void cleanup() {
        logger.close();
    }

    @Test
    @Order(1)
    void testCrud() {
        StormifyManager s = stormify();

        // Drop tables in FK dependency order, then create
        TestDDL.dropTable("child");
        TestDDL.dropTable("test");
        TestDDL.dropTable("dual_key");
        s.executeUpdate(TestDDL.createTable("test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));
        s.executeUpdate(TestDDL.createTable("child",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType() + ", " +
                        TestDDL.intColumn("parent") + ", " + TestDDL.foreignKey("parent", "test", "id")));
        s.executeUpdate(TestDDL.createTable("dual_key",
                TestDDL.intNotNull("id1") + ", " + TestDDL.intNotNull("id2") +
                        ", data " + TestDDL.textType() + ", PRIMARY KEY (id1, id2)"));
        logger.get(); // clear DDL logs

        // Basic CRUD
        TestC tst = new TestC(1, "Test1");
        tst.create();
        assertEquals("[TestC(id=1, name=Test1)]", s.findAll(TestC.class, null).toString());
        logger.get();

        tst.setName("Test2");
        tst.update();
        assertEquals("[TestC(id=1, name=Test2)]", s.findAll(TestC.class, null).toString());
        logger.get();

        tst.setId(2);
        tst.setName("Test2");
        tst.create();
        assertEquals("[TestC(id=1, name=Test2), TestC(id=2, name=Test2)]",
                s.findAll(TestC.class, "ORDER BY id").toString());
        logger.get();

        tst.setId(1);
        tst.delete();
        assertEquals("[TestC(id=2, name=Test2)]", s.findAll(TestC.class, null).toString());
        logger.get();

        // Populate
        IntStream.rangeClosed(3, 5).forEach(id -> new TestC(id, "Test" + id).create());
        assertEquals(
                "[TestC(id=3, name=Test3), TestC(id=4, name=Test4), TestC(id=5, name=Test5)]",
                s.read(TestC.class, "SELECT * FROM test where id>=? ORDER BY id", 3).toString()
        );
        logger.get();

        tst.setId(2);
        tst.setName(null);
        String before = tst.toString();
        tst.populate();
        tst.populate();
        tst.populate();
        assertEquals("TestC(id=2, name=null)", before, "before");
        assertEquals("TestC(id=2, name=Test2)", tst.toString(), "after");
        logger.get();

        // Read single item
        assertEquals(
                "TestC(id=4, name=Test4)",
                s.readOne(TestC.class, "SELECT * FROM test WHERE id = ?", 4).toString()
        );

        // Child-parent relationship
        new Child(1, "Child1", s.findById(TestC.class, 5)).create();
        Child c = s.readOne(Child.class, "SELECT c.id, c.parent FROM child c ");
        c.getParent().populate();
        assertEquals("Child(id=1, name=null, parent=TestC(id=5, name=Test5))", c.toString());
        logger.get();

        assertEquals("[Child(id=1, name=Child1, parent=TestC(id=5, name=Test5))]",
                c.getParent().getDetails(Child.class).toString());
        assertEquals(
                "[TestC(id=3, name=Test3), TestC(id=2, name=Test2)]",
                s.findAll(TestC.class, "WHERE id < ? ORDER BY id DESC", 4).toString()
        );
        logger.get();

        // Dual key
        DualKey dk1 = new DualKey(1, 2, "Data1");
        dk1.create();
        DualKey dk2 = new DualKey(3, 42, "Data2");
        dk2.create();
        assertEquals(
                "[DualKey(id1=1, id2=2, data=Data1), DualKey(id1=3, id2=42, data=Data2)]",
                s.findAll(DualKey.class, "ORDER BY id1").toString()
        );
        dk1.setData("Data3");
        dk1.update();
        dk2.delete();
        assertEquals("[DualKey(id1=1, id2=2, data=Data3)]", s.findAll(DualKey.class, null).toString());
        DualKey dk1P1 = new DualKey(1, 2);
        dk1P1.populate();
        assertEquals(dk1, dk1P1);
        logger.get();
    }

    @Test
    @Order(2)
    void testOnTheFlyFields() {
        StormifyManager s = stormify();

        // Setup: table has id + name, but FlyView entity has id + name + label
        TestDDL.dropTable("fly_test");
        s.executeUpdate(TestDDL.createTable("fly_test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));
        s.executeUpdate("INSERT INTO fly_test (id, name) VALUES (?, ?)", 1, "Alice");
        s.executeUpdate("INSERT INTO fly_test (id, name) VALUES (?, ?)", 2, "Bob");
        logger.get();

        // On-the-fly alias: "name as label" should fill the "label" entity field
        // "label" column doesn't exist in fly_test table — it comes from the alias
        FlyView withLabel = s.readOne(FlyView.class,
                "SELECT id, name, name as label FROM fly_test WHERE id = ?", 1);
        assertEquals(1, withLabel.getId());
        assertEquals("Alice", withLabel.getName());
        assertEquals("Alice", withLabel.getLabel());

        // Fewer columns than entity fields — "label" stays null
        FlyView partial = s.readOne(FlyView.class,
                "SELECT id, name FROM fly_test WHERE id = ?", 2);
        assertEquals(2, partial.getId());
        assertEquals("Bob", partial.getName());
        assertNull(partial.getLabel());

        // Only the on-the-fly field, no "name" — "name" stays null, "label" fills
        FlyView onlyLabel = s.readOne(FlyView.class,
                "SELECT id, name as label FROM fly_test WHERE id = ?", 1);
        assertEquals(1, onlyLabel.getId());
        assertNull(onlyLabel.getName());
        assertEquals("Alice", onlyLabel.getLabel());

        // Extra column not in entity (bonus) — should not crash, just ignored
        FlyView withBonus = s.readOne(FlyView.class,
                "SELECT id, name, name as label, id as bonus FROM fly_test WHERE id = ?", 1);
        assertEquals(1, withBonus.getId());
        assertEquals("Alice", withBonus.getName());
        assertEquals("Alice", withBonus.getLabel());

        // Mixed case aliases — must still map correctly (Oracle returns UPPERCASE, others lowercase)
        FlyView mixedCase = s.readOne(FlyView.class,
                "SELECT id as Id, name as NaMe, name as LaBeL FROM fly_test WHERE id = ?", 1);
        assertEquals(1, mixedCase.getId());
        assertEquals("Alice", mixedCase.getName());
        assertEquals("Alice", mixedCase.getLabel());

        // Purely on-the-fly: computed values with aliases, no real table columns
        FlyView computed = s.readOne(FlyView.class,
                TestDDL.selectExpr("42 as id, 'hello' as name, 'world' as label"));
        assertEquals(42, computed.getId());
        assertEquals("hello", computed.getName());
        assertEquals("world", computed.getLabel());

        logger.get();
    }

    @Test
    @Order(3)
    void testTimestamps() {
        StormifyManager s = stormify();

        TestDDL.dropTable("time_data");
        TestDDL.dropTable("time");
        s.executeUpdate(TestDDL.createTable("time_data",
                TestDDL.intPrimaryKey("id") + ", time_val " + TestDDL.timestampType()));

        s.executeUpdate(TestDDL.createTable("time",
                TestDDL.intPrimaryKey("id") + ", time " + TestDDL.timestampType()));
        logger.get();

        new Time(1, LocalDateTime.of(2024, Month.APRIL, 1, 12, 0, 0)).create();
        new Time2(2, LocalDate.of(2024, Month.APRIL, 1)).create();

        List<Time> times = s.findAll(Time.class, null);
        assertEquals(2, times.size());
        assertEquals(1, times.get(0).getId());
        assertEquals(2, times.get(1).getId());
        logger.get();
    }

    @Test
    @Order(4)
    void testAutoIncrement() {
        assumeTrue(TestDDL.supportsAutoIncrement(),
                "Skipping auto-increment test for " + stormify().getSqlDialect());

        StormifyManager s = stormify();

        String autoPk = TestDDL.autoIncrementPrimaryKey("id");
        TestDDL.dropTable("auto_increment");
        s.executeUpdate(TestDDL.createTable("auto_increment",
                autoPk + ", name " + TestDDL.textType()));
        logger.get();

        AutoIncrement a1 = new AutoIncrement("Test1");
        a1.create();
        AutoIncrement a2 = new AutoIncrement("Test2");
        a2.create();
        assertEquals(1, a1.getId());
        assertEquals(2, a2.getId());
        assertEquals(2, (int) s.readOne(int.class, "SELECT COUNT(*) FROM auto_increment"));
        logger.get();
    }

    @Test
    @Order(5)
    void testTransactions() {
        StormifyManager s = stormify();

        TestDDL.dropTable("tx_test");
        s.executeUpdate(TestDDL.createTable("tx_test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));
        logger.get();

        // Use direct SQL for transaction testing since we need a fresh table
        s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", 2, "Test2");
        logger.get();

        // Test rollback
        final String[] resultOnTransaction = {""};
        try {
            s.transaction(() -> {
                IntStream.rangeClosed(3, 5).forEach(id ->
                        s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test" + id));
                resultOnTransaction[0] = s.read(String.class,
                        "SELECT name FROM tx_test ORDER BY id").toString();
                throw new Exception("Request Rollback");
            });
        } catch (Exception ex) {
            assertEquals("Request Rollback", ex.getCause().getMessage());
        }
        assertEquals("[Test2, Test3, Test4, Test5]", resultOnTransaction[0]);
        assertEquals("[Test2]", s.read(String.class, "SELECT name FROM tx_test ORDER BY id").toString());
        logger.get();

        // Test commit
        s.transaction(() -> IntStream.rangeClosed(3, 5).forEach(id ->
                s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test" + id)));
        assertEquals("[Test2, Test3, Test4, Test5]",
                s.read(String.class, "SELECT name FROM tx_test ORDER BY id").toString());
        logger.get();

        // Cleanup for nested transaction test
        s.transaction(() -> IntStream.rangeClosed(3, 5).forEach(id ->
                s.executeUpdate("DELETE FROM tx_test WHERE id = ?", id)));
        assertEquals("[Test2]", s.read(String.class, "SELECT name FROM tx_test ORDER BY id").toString());
        logger.get();

        // Test nested transactions with rollback
        s.transaction(() -> {
            IntStream.rangeClosed(3, 4).forEach(id ->
                    s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test" + id));
            assertEquals("[Test2, Test3, Test4]",
                    s.read(String.class, "SELECT name FROM tx_test ORDER BY id").toString());

            try {
                s.transaction(() -> {
                    IntStream.rangeClosed(5, 6).forEach(id ->
                            s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test" + id));
                    assertEquals("[Test2, Test3, Test4, Test5, Test6]",
                            s.read(String.class, "SELECT name FROM tx_test ORDER BY id").toString());
                    throw new Exception("Request Rollback");
                });
            } catch (Exception ex) {
                assertEquals("Request Rollback", ex.getCause().getMessage());
            }

            assertEquals("[Test2, Test3, Test4]",
                    s.read(String.class, "SELECT name FROM tx_test ORDER BY id").toString());

            s.transaction(() -> {
                IntStream.rangeClosed(5, 6).forEach(id ->
                        s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test" + id));
                s.transaction(() -> {
                    IntStream.rangeClosed(3, 6).forEach(id ->
                            s.executeUpdate("DELETE FROM tx_test WHERE id = ?", id));
                    assertEquals("[Test2]",
                            s.read(String.class, "SELECT name FROM tx_test ORDER BY id").toString());
                });
            });
        });
        assertEquals("[Test2]", s.read(String.class, "SELECT name FROM tx_test ORDER BY id").toString());
        logger.get();
    }

    @Test
    @Order(6)
    void testAutoTable() {
        StormifyManager s = stormify();

        TestDDL.dropTable("auto_child");
        TestDDL.dropTable("auto_parent");
        s.executeUpdate(TestDDL.createTable("auto_parent",
                TestDDL.intPrimaryKey("id") + ", data " + TestDDL.textType() + ", other " + TestDDL.textType()));
        s.executeUpdate(TestDDL.createTable("auto_child",
                TestDDL.intPrimaryKey("id") + ", data " + TestDDL.textType() + ", " +
                        TestDDL.intColumn("parent") + ", " + TestDDL.foreignKey("parent", "auto_parent", "id")));
        logger.get();

        AutoParent parent = new AutoParent();
        parent.setData("I am a parent");
        parent.setOther("This is my other fields");
        parent.setId(17);

        AutoChild child = new AutoChild();
        child.setData("I am a child");
        child.setParent(parent);
        child.setId(23);

        parent.create();
        child.create();

        AutoChild ch = s.findAll(AutoChild.class, null).get(0);
        logger.get();

        assertEquals("I am a child", ch.getData());
        assertNull(ch.getParent().data);
        assertEquals("AutoParent[id=17]", ch.getParent().toString());
        assertNull(ch.getParent().data);
        assertEquals("", logger.get());
        assertEquals("I am a parent", ch.getParent().getData());
        assertEquals("I am a parent", ch.getParent().data);
        logger.get();

        // Create additional children
        AutoChild child2 = new AutoChild();
        child2.setData("I am a child 2");
        child2.setParent(parent);
        child2.setId(2);
        child2.create();

        AutoChild child3 = new AutoChild();
        child3.setData("I am a child 3");
        child3.setParent(parent);
        child3.setId(3);
        child3.create();
        logger.get();

        // Check parent's children (order may vary by database, so sort by id for comparison)
        List<Integer> childrenIds = new ArrayList<>();
        for (AutoChild ac : parent.getChildren()) childrenIds.add(ac.getId());
        Collections.sort(childrenIds);
        assertEquals("[2, 3, 23]", childrenIds.toString());
        logger.get();

        // Clear parent's children and verify
        assertFalse(parent.getChildren().isEmpty());
        parent.setChildren(Collections.emptyList());
        assertEquals("[]", parent.getChildren().toString());
        assertEquals("", logger.get());
    }

    @Test
    @Order(7)
    void testDoubleDbNames() {
        StormifyManager s = stormify();

        TestDDL.dropTable("double_db_name");
        s.executeUpdate(TestDDL.createTable("double_db_name",
                TestDDL.intColumn("id") + ", name " + TestDDL.textType()));
        s.getTableInfo(DoubleDbName.class).checkConsistency();
        logger.get();

        DoubleDbName ddn = new DoubleDbName();
        ddn.setId(1);
        ddn.setName1("Name1");
        ddn.setName2("Name2");

        ddn.create();
        DoubleDbName test1 = stormify().findById(DoubleDbName.class, 1);
        assertEquals("Name1", test1.getName1());
        assertEquals("Name1", test1.getName2());

        ddn.update();
        DoubleDbName test2 = stormify().findById(DoubleDbName.class, 1);
        assertEquals("Name2", test2.getName1());
        assertEquals("Name2", test2.getName2());
        logger.get();
    }

    @Test
    @Order(8)
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
        logger.get();

        String selectExpr = TestDDL.selectExpr("( 1 + 2 ) * 3");

        for (int i = 1; i <= 1000; i++)
            assertEquals(9, s.readOne(Integer.class, selectExpr));

        ExecutorService executorService = Executors.newFixedThreadPool(100);
        for (int i = 1; i <= 1000; i++)
            executorService.submit(() -> {
                assertEquals(9, (int) stormify().readOne(Integer.class, selectExpr));
                countSimple.incrementAndGet();
            });

        for (int i = 1; i <= 1000; i++)
            executorService.submit(() -> {
                assertEquals(
                        model.getData(),
                        stormify().readOne(StressTable.class,
                                "SELECT * FROM stress_table WHERE id = ?", model.getId()).getData()
                );
                countSelect.incrementAndGet();
            });

        for (int i = 1; i <= 1000; i++)
            executorService.submit(() -> {
                int nextId = countInserts.incrementAndGet();
                new StressTable(nextId, "Index #" + nextId).create();
            });

        executorService.shutdown();

        try {
            if (!executorService.awaitTermination(100, TimeUnit.SECONDS))
                executorService.shutdownNow();
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        assertEquals(1000, s.readOne(int.class, "SELECT COUNT(*) FROM stress_table"));

        // Transaction rollback under stress
        try {
            s.transaction(() -> {
                for (int i = 500; i < 600; i++)
                    new StressTable(i).delete();
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
            int from = i * 100;
            int upto = (i + 1) * 100;
            threads.add(new Thread(() -> removeAndFail(from, upto)));
        }
        threads.forEach(Thread::start);
        threads.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                System.exit(-1);
            }
        });

        assertEquals(1000, s.readOne(int.class, "SELECT COUNT(*) FROM stress_table"));
        assertEquals(1000, countSimple.get(), "Simple queries");
        assertEquals(1000, countSelect.get(), "ORM queries");
        assertEquals(1000, countInserts.get(), "Insert queries");

        logger.get(); // ignore logging
    }

    private static void removeAndFail(int from, int upto) {
        int area = upto - from;
        int p1 = from + area / 4;
        int p2 = from + area / 2;
        int p3 = from + area * 3 / 4;
        try {
            stormify().transaction(() -> {
                for (int i = from; i < p1; i++)
                    new StressTable(i).delete();
                try {
                    stormify().transaction(() -> {
                        for (int i = p1; i < p2; i++)
                            new StressTable(i).delete();
                        try {
                            stormify().transaction(() -> {
                                for (int i = p2; i < p3; i++)
                                    new StressTable(i).delete();
                                try {
                                    stormify().transaction(() -> {
                                        for (int i = p3; i < upto; i++)
                                            new StressTable(i).delete();
                                        throw new Exception("Request Rollback 1");
                                    });
                                } catch (Exception ex) {
                                    checkError("Request Rollback 1", ex);
                                }
                                throw new Exception("Request Rollback 2");
                            });
                        } catch (Exception ex) {
                            checkError("Request Rollback 2", ex);
                        }
                        throw new Exception("Request Rollback 3");
                    });
                } catch (Exception ex) {
                    checkError("Request Rollback 3", ex);
                }
                throw new Exception("Request Rollback 4");
            });
        } catch (Exception ex) {
            checkError("Request Rollback 4", ex);
        }
    }

    private static void checkError(String message, Throwable th) {
        if (!th.getCause().getMessage().equals(message)) {
            th.printStackTrace();
            System.exit(-1);
        }
    }
}
