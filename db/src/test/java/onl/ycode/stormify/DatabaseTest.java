package onl.ycode.stormify;

// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

import ch.qos.logback.classic.Level;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import onl.ycode.stormify.pojos.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DatabaseTest {

    private static final TestLogger logger = new TestLogger();

    @BeforeEach
    public void setup() {
        try {
            HikariConfig config = new HikariConfig("/hikari.properties"); // Replace with the actual config name
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.zaxxer.hikari")).setLevel(Level.ERROR);
            StormifyManager.stormify().setDataSource(new HikariDataSource(config));
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("********** Database not available **********");
            return;
        }

        try (Connection connection = StormifyManager.stormify().getDataSource().getConnection()) {
            if (!connection.isValid(10)) {
                throw new IllegalStateException("Database connection not available");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Database connection not available", e);
        }
        stormify().registerPrimaryKeyResolver(0, (a, c) -> c.toLowerCase().startsWith("id"));
    }

    @AfterAll
    public static void cleanup() {
        logger.close();
    }

    @Test
    void launchTests() {
        StormifyManager s = stormify();
        if (!s.isDataSourcePresent())
            return;

        testDoubleDbNames();
        stressTest();
        testAutoTable();

        s.executeUpdate("DROP TABLE IF EXISTS " + new Time().tableName());
        s.executeUpdate("DROP TABLE IF EXISTS " + new Child().tableName());
        s.executeUpdate("DROP TABLE IF EXISTS " + new TestC().tableName());
        s.executeUpdate("DROP TABLE IF EXISTS " + new DualKey().tableName());

        assertEquals("DROP TABLE IF EXISTS time\n" +
                        "DROP TABLE IF EXISTS child\n" +
                        "DROP TABLE IF EXISTS test\n" +
                        "DROP TABLE IF EXISTS dual_key\n",
                logger.get()
        );

        s.executeUpdate("CREATE TABLE IF NOT EXISTS " + new TestC().tableName() + " (id INT PRIMARY KEY, name TEXT)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS " + new Child().tableName() + " (id INT PRIMARY KEY, name TEXT, parent INT, FOREIGN KEY(parent) REFERENCES " + new TestC().tableName() + "(id))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS " + new Time().tableName() + " (id INT PRIMARY KEY, time TIMESTAMP)");
        s.executeUpdate("CREATE TABLE " + new DualKey().tableName() + " (id1 INT NOT NULL, id2 INT NOT NULL, data TEXT, PRIMARY KEY (id1, id2))");

        assertEquals(
                "CREATE TABLE IF NOT EXISTS test (id INT PRIMARY KEY, name TEXT)\n" +
                        "CREATE TABLE IF NOT EXISTS child (id INT PRIMARY KEY, name TEXT, parent INT, FOREIGN KEY(parent) REFERENCES test(id))\n" +
                        "CREATE TABLE IF NOT EXISTS time (id INT PRIMARY KEY, time TIMESTAMP)\n" +
                        "CREATE TABLE dual_key (id1 INT NOT NULL, id2 INT NOT NULL, data TEXT, PRIMARY KEY (id1, id2))\n",
                logger.get()
        );

        testAutoIncrement(logger);

        new Time(1, LocalDateTime.of(2024, Month.APRIL, 1, 12, 0, 0)).create();
        new Time2(2, LocalDate.of(2024, Month.APRIL, 1)).create();
        assertEquals(
                "INSERT INTO time (id, time) VALUES (?, ?) -- [1, 2024-04-01T12:00]\n" +
                        "INSERT INTO time (id, time) VALUES (?, ?) -- [2, 2024-04-01]\n",
                logger.get()
        );

        assertEquals(
                "[Time(id=1, time=2024-04-01T12:00), Time(id=2, time=2024-04-01T00:00)]",
                s.findAll(Time.class, null).toString()
        );
        assertEquals("SELECT * FROM time\n", logger.get());

        TestC tst = new TestC(1, "Test1");
        tst.create();
        assertEquals("[TestC(id=1, name=Test1)]", s.findAll(TestC.class, null).toString());
        assertEquals(
                "INSERT INTO test (id, name) VALUES (?, ?) -- [1, Test1]\nSELECT * FROM test\n",
                logger.get()
        );

        tst.setName("Test2");
        tst.update();
        assertEquals("[Test2(id=1, name=Test2, extra=null)]", s.findAll(Test2.class, null).toString());
        assertEquals(
                "UPDATE test SET id = ?, name = ? WHERE id = ? -- [1, Test2, 1]\nSELECT * FROM test\n",
                logger.get()
        );

        tst.setId(2);
        tst.setName("Test2");
        tst.create();
        assertEquals("[TestC(id=1, name=Test2), TestC(id=2, name=Test2)]", s.findAll(TestC.class, null).toString());
        assertEquals("INSERT INTO test (id, name) VALUES (?, ?) -- [2, Test2]\nSELECT * FROM test\n", logger.get());

        tst.setId(1);
        tst.delete();
        assertEquals("[TestC(id=2, name=Test2)]", s.findAll(TestC.class, null).toString());
        assertEquals("DELETE FROM test WHERE id = ? -- [1]\nSELECT * FROM test\n", logger.get());

        transactionalTests(logger);

        IntStream.rangeClosed(3, 5).forEach(id -> new TestC(id, "Test" + id).create());
        assertEquals(
                "[TestC(id=3, name=Test3), TestC(id=4, name=Test4), TestC(id=5, name=Test5)]",
                s.read(TestC.class, "SELECT * FROM test where id>=?", 3).toString()
        );
        assertEquals(
                "INSERT INTO test (id, name) VALUES (?, ?) -- [3, Test3]\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [4, Test4]\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [5, Test5]\n" +
                        "SELECT * FROM test where id>=? -- [3]\n",
                logger.get()
        );

        tst.setId(2);
        tst.setName(null);
        String before = tst.toString();
        tst.populate();
        tst.populate();
        tst.populate();
        assertEquals("TestC(id=2, name=null)", before, "before");
        assertEquals("TestC(id=2, name=Test2)", tst.toString(), "after");
        assertEquals("SELECT * FROM test WHERE id = ? -- [2]\n", logger.get());

        assertEquals(
                "Test2(id=4, name=Test4, extra=null)",
                s.readOne(Test2.class, "SELECT * FROM test WHERE id = ?", 4).toString()
        );
        new Child(1, "Child1", s.findById(TestC.class, 5)).create();
        Child c = s.readOne(Child.class, "SELECT c.id, c.parent FROM child c ");
        c.getParent().populate();
        assertEquals("Child(id=1, name=null, parent=TestC(id=5, name=Test5))", c.toString());
        assertEquals(
                "SELECT * FROM test WHERE id = ? -- [4]\n" +
                        "SELECT * FROM test WHERE id = ? -- [5]\n" +
                        "INSERT INTO child (id, name, parent) VALUES (?, ?, ?) -- [1, Child1, 5]\n" +
                        "SELECT c.id, c.parent FROM child c \n" +
                        "SELECT * FROM test WHERE id = ? -- [5]\n",
                logger.get()
        );

        assertEquals("[Child(id=1, name=Child1, parent=TestC(id=5, name=Test5))]", c.getParent().getDetails(Child.class).toString());
        assertEquals(
                "[TestC(id=3, name=Test3), TestC(id=2, name=Test2)]",
                s.findAll(TestC.class, "WHERE id < ? ORDER BY id DESC", 4).toString()
        );
        assertEquals(
                "SELECT * FROM child WHERE parent = ? -- [5]\n" +
                        "SELECT * FROM test WHERE id < ? ORDER BY id DESC -- [4]\n",
                logger.get()
        );

        DualKey dk1 = new DualKey(1, 2, "Data1");
        dk1.create();
        DualKey dk2 = new DualKey(3, 42, "Data2");
        dk2.create();
        assertEquals(
                "[DualKey(id1=1, id2=2, data=Data1), DualKey(id1=3, id2=42, data=Data2)]",
                s.findAll(DualKey.class, null).toString()
        );
        dk1.setData("Data3");
        dk1.update();
        dk2.delete();
        assertEquals("[DualKey(id1=1, id2=2, data=Data3)]", s.findAll(DualKey.class, null).toString());
        DualKey dk1P1 = new DualKey(1, 2);
        dk1P1.populate();
        assertEquals(dk1, dk1P1);

        assertEquals(
                "INSERT INTO dual_key (data, id1, id2) VALUES (?, ?, ?) -- [Data1, 1, 2]\n" +
                        "INSERT INTO dual_key (data, id1, id2) VALUES (?, ?, ?) -- [Data2, 3, 42]\n" +
                        "SELECT * FROM dual_key\n" +
                        "UPDATE dual_key SET data = ?, id1 = ?, id2 = ? WHERE id1 = ? AND id2 = ? -- [Data3, 1, 2, 1, 2]\n" +
                        "DELETE FROM dual_key WHERE id1 = ? AND id2 = ? -- [3, 42]\n" +
                        "SELECT * FROM dual_key\n" +
                        "SELECT * FROM dual_key WHERE id1 = ? AND id2 = ? -- [1, 2]\n",
                logger.get()
        );
    }

    private void testAutoTable() {
        StormifyManager s = stormify();

        AutoParent parent = new AutoParent();
        parent.setData("I am a parent");
        parent.setOther("This is my other fields");
        parent.setId(17);

        AutoChild child = new AutoChild();
        child.setData("I am a child");
        child.setParent(parent);
        child.setId(23);

        // Execute SQL statements
        s.executeUpdate("DROP TABLE IF EXISTS " + new AutoChild().tableName());
        s.executeUpdate("DROP TABLE IF EXISTS " + new AutoParent().tableName());
        s.executeUpdate("CREATE TABLE IF NOT EXISTS " + new AutoParent().tableName() + " (id INT PRIMARY KEY, data TEXT, other TEXT)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS " + new AutoChild().tableName() +
                " (id INT PRIMARY KEY, data TEXT, parent INT, FOREIGN KEY(parent) REFERENCES " + new AutoParent().tableName() + "(id))");

        assertEquals("DROP TABLE IF EXISTS child\n" +
                        "DROP TABLE IF EXISTS parent\n" +
                        "CREATE TABLE IF NOT EXISTS parent (id INT PRIMARY KEY, data TEXT, other TEXT)\n" +
                        "CREATE TABLE IF NOT EXISTS child (id INT PRIMARY KEY, data TEXT, parent INT, FOREIGN KEY(parent) REFERENCES parent(id))\n",
                logger.get());

        // Create the parent and child records in the database
        parent.create();
        child.create();

        AutoChild ch = s.findAll(AutoChild.class, null).get(0);
        assertEquals("INSERT INTO parent (data, id, other) VALUES (?, ?, ?) -- [I am a parent, 17, This is my other fields]\n" +
                        "INSERT INTO child (data, id, parent) VALUES (?, ?, ?) -- [I am a child, 23, 17]\n" +
                        "SELECT * FROM child\n",
                logger.get());

        // Verify properties of the child and parent
        assertEquals("I am a child", ch.getData());
        assertNull(ch.getParent().data);
        assertEquals("AutoParent[id=17]", ch.getParent().toString());
        assertNull(ch.getParent().data);
        assertEquals("", logger.get());
        assertEquals("I am a parent", ch.getParent().getData());
        assertEquals("I am a parent", ch.getParent().data);
        assertEquals("SELECT * FROM parent WHERE id = ? -- [17]\n", logger.get());

        // Create additional children
        new AutoChild() {{
            setData("I am a child 2");
            setParent(parent);
            setId(2);
        }}.create();

        new AutoChild() {{
            setData("I am a child 3");
            setParent(parent);
            setId(3);
        }}.create();

        assertEquals("INSERT INTO child (data, id, parent) VALUES (?, ?, ?) -- [I am a child 2, 2, 17]\n" +
                        "INSERT INTO child (data, id, parent) VALUES (?, ?, ?) -- [I am a child 3, 3, 17]\n",
                logger.get());

        // Check parent's children
        assertEquals("[AutoChild[id=2], AutoChild[id=3], AutoChild[id=23]]", parent.getChildren().toString());
        assertEquals("SELECT * FROM child WHERE parent = ? -- [17]\n", logger.get());

        // Clear parent's children and verify
        assertEquals("[AutoChild[id=2], AutoChild[id=3], AutoChild[id=23]]", parent.getChildren().toString());
        assertEquals("[AutoChild[id=2], AutoChild[id=3], AutoChild[id=23]]", parent.getChildren().toString());
        parent.setChildren(Collections.emptyList());
        assertEquals("[]", parent.getChildren().toString());
        assertEquals("", logger.get());
    }

    private void testDoubleDbNames() {
        StormifyManager s = stormify();

        s.getTableInfo(DoubleDbName.class).checkConsistency();


        s.executeUpdate("DROP TABLE IF EXISTS " + new DoubleDbName().tableName());
        s.executeUpdate("CREATE TABLE IF NOT EXISTS " + new DoubleDbName().tableName() + " (id INT, name TEXT)");
        assertEquals(
                "double_db_name{\uD83C\uDFF7{\uD83D\uDD04id : int, primary} \uD83C\uDFF7{\uD83D\uDD04\uD83D\uDEB7name1 \uD83D\uDCBEname : String} \uD83C\uDFF7{\uD83D\uDD04\uD83D\uDEB7name2 \uD83D\uDCBEname : String} [\uD83C\uDFF7{\uD83D\uDD04id : int, primary}]}",
                stormify().getTableInfo(DoubleDbName.class).toString()
        );
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

        s.executeUpdate("DROP TABLE IF EXISTS " + new DoubleDbName().tableName());

        assertEquals(
                "DROP TABLE IF EXISTS double_db_name\n" +
                        "CREATE TABLE IF NOT EXISTS double_db_name (id INT, name TEXT)\n" +
                        "INSERT INTO double_db_name (id, name) VALUES (?, ?) -- [1, Name1]\n" +
                        "SELECT * FROM double_db_name WHERE id = ? -- [1]\n" +
                        "UPDATE double_db_name SET id = ?, name = ? WHERE id = ? -- [1, Name2, 1]\n" +
                        "SELECT * FROM double_db_name WHERE id = ? -- [1]\n" +
                        "DROP TABLE IF EXISTS double_db_name\n",
                logger.get()
        );
    }

    private void testAutoIncrement(TestLogger logger) {
        StormifyManager s = stormify();

        s.executeUpdate("DROP TABLE IF EXISTS " + new AutoIncrement().tableName());
        s.executeUpdate("CREATE TABLE IF NOT EXISTS " + new AutoIncrement().tableName() + " (id INT PRIMARY KEY AUTO_INCREMENT, name TEXT)");
        AutoIncrement a1 = new AutoIncrement("Test1");
        a1.create();
        AutoIncrement a2 = new AutoIncrement("Test2");
        a2.create();
        s.executeUpdate("DROP TABLE IF EXISTS " + new AutoIncrement().tableName());
        assertEquals(1, a1.getId());
        assertEquals(2, a2.getId());
        assertEquals(
                "DROP TABLE IF EXISTS auto_increment\n" +
                        "CREATE TABLE IF NOT EXISTS auto_increment (id INT PRIMARY KEY AUTO_INCREMENT, name TEXT)\n" +
                        "INSERT INTO auto_increment (id, name) VALUES (?, ?) -- [0, Test1]\n" +
                        "INSERT INTO auto_increment (id, name) VALUES (?, ?) -- [0, Test2]\n" +
                        "DROP TABLE IF EXISTS auto_increment\n",
                logger.get()
        );
    }

    private void transactionalTests(TestLogger logger) {
        StormifyManager s = stormify();

        final String[] resultOnTransaction = {""};
        try {
            s.transaction(() -> {
                IntStream.rangeClosed(3, 5).forEach(id -> new TestC(id, "Test" + id).create());
                resultOnTransaction[0] = s.findAll(TestC.class, null).toString();
                throw new Exception("Request Rollback");
            });
        } catch (Exception ex) {
            assertEquals("Request Rollback", ex.getCause().getMessage());
        }
        assertEquals(
                "[TestC(id=2, name=Test2), TestC(id=3, name=Test3), TestC(id=4, name=Test4), TestC(id=5, name=Test5)]",
                resultOnTransaction[0]
        );
        assertEquals("[TestC(id=2, name=Test2)]", s.findAll(TestC.class, null).toString());
        assertEquals(
                "Start transaction\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [3, Test3]\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [4, Test4]\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [5, Test5]\n" +
                        "SELECT * FROM test\n" +
                        "Rollback transaction\n" +
                        "SELECT * FROM test\n",
                logger.get()
        );

        s.transaction(() -> IntStream.rangeClosed(3, 5).forEach(id -> new TestC(id, "Test" + id).create()));
        assertEquals(
                "[TestC(id=2, name=Test2), TestC(id=3, name=Test3), TestC(id=4, name=Test4), TestC(id=5, name=Test5)]",
                s.findAll(TestC.class, null).toString()
        );
        assertEquals(
                "Start transaction\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [3, Test3]\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [4, Test4]\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [5, Test5]\n" +
                        "Commit transaction\n" +
                        "SELECT * FROM test\n",
                logger.get()
        );
        s.transaction(() -> IntStream.rangeClosed(3, 5).forEach(id -> new TestC(id, "Test" + id).delete()));
        assertEquals("[TestC(id=2, name=Test2)]", s.findAll(TestC.class, null).toString());
        assertEquals(
                "Start transaction\n" +
                        "DELETE FROM test WHERE id = ? -- [3]\n" +
                        "DELETE FROM test WHERE id = ? -- [4]\n" +
                        "DELETE FROM test WHERE id = ? -- [5]\n" +
                        "Commit transaction\n" +
                        "SELECT * FROM test\n",
                logger.get()
        );

        s.transaction(() -> {
            IntStream.rangeClosed(3, 4).forEach(id -> new TestC(id, "Test" + id).create());
            assertEquals(
                    "[TestC(id=2, name=Test2), TestC(id=3, name=Test3), TestC(id=4, name=Test4)]",
                    s.findAll(TestC.class, null).toString()
            );
            try {
                s.transaction(() -> {
                    IntStream.rangeClosed(5, 6).forEach(id -> new TestC(id, "Test" + id).create());
                    assertEquals(
                            "[TestC(id=2, name=Test2), TestC(id=3, name=Test3), TestC(id=4, name=Test4), TestC(id=5, name=Test5), TestC(id=6, name=Test6)]",
                            s.findAll(TestC.class, null).toString()
                    );
                    throw new Exception("Request Rollback");
                });
            } catch (Exception ex) {
                assertEquals("Request Rollback", ex.getCause().getMessage());
            }
            assertEquals(
                    "[TestC(id=2, name=Test2), TestC(id=3, name=Test3), TestC(id=4, name=Test4)]",
                    s.findAll(TestC.class, null).toString()
            );
            s.transaction(() -> {
                IntStream.rangeClosed(5, 6).forEach(id -> new TestC(id, "Test" + id).create());
                StringBuilder nameCatcher = new StringBuilder();
                assertEquals(
                        3,
                        s.readCursor(TestC.class, "SELECT * FROM test WHERE id IN ? OR id IN ?",
                                it -> nameCatcher.append(it.getName()),
                                new Object[]{new TestC(2), new TestC(4), new TestC(6)},
                                new Object[]{new TestC(6), new TestC(2)}),
                        "The number of results should be 3"
                );
                assertEquals("Test2Test4Test6", nameCatcher.toString());
                s.transaction(() -> {
                    IntStream.rangeClosed(3, 6).forEach(id -> new TestC(id, "Test" + id).delete());
                    assertEquals(
                            "[TestC(id=2, name=Test2)]",
                            s.findAll(TestC.class, null).toString()
                    );
                });
            });
        });
        assertEquals(
                "Start transaction\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [3, Test3]\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [4, Test4]\n" +
                        "SELECT * FROM test\n" +
                        "Start inner transaction #1\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [5, Test5]\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [6, Test6]\n" +
                        "SELECT * FROM test\n" +
                        "Rollback inner transaction #1\n" +
                        "SELECT * FROM test\n" +
                        "Start inner transaction #1\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [5, Test5]\n" +
                        "INSERT INTO test (id, name) VALUES (?, ?) -- [6, Test6]\n" +
                        "SELECT * FROM test WHERE id IN (?, ?, ?) OR id IN (?, ?) -- [2, 4, 6, 6, 2]\n" +
                        "Start inner transaction #2\n" +
                        "DELETE FROM test WHERE id = ? -- [3]\n" +
                        "DELETE FROM test WHERE id = ? -- [4]\n" +
                        "DELETE FROM test WHERE id = ? -- [5]\n" +
                        "DELETE FROM test WHERE id = ? -- [6]\n" +
                        "SELECT * FROM test\n" +
                        "Commit inner transaction #2\n" +
                        "Commit inner transaction #1\n" +
                        "Commit transaction\n",
                logger.get()
        );
    }

    private static void stressTest() {
        AtomicInteger countSimple = new AtomicInteger(0);
        AtomicInteger countSelect = new AtomicInteger(0);
        AtomicInteger countInserts = new AtomicInteger(0);
        StormifyManager s = stormify();

        s.executeUpdate("DROP TABLE IF EXISTS " + new StressTable().tableName());
        s.executeUpdate("CREATE TABLE IF NOT EXISTS " + new StressTable().tableName() + " (id INT PRIMARY KEY, data TEXT)");
        StressTable model = new StressTable(17, "42");
        model.create();

        for (int i = 1; i <= 1000; i++)
            assertEquals(9, s.readOne(Integer.class, "SELECT ( 1 + 2 ) * 3"));

        ExecutorService executorService = Executors.newFixedThreadPool(100);
        for (int i = 1; i <= 1000; i++)
            executorService.submit(() -> {
                assertEquals(9, (int) stormify().readOne(Integer.class, "SELECT ( 1 + 2 ) * 3"));
                countSimple.incrementAndGet();
            });

        for (int i = 1; i <= 1000; i++)
            executorService.submit(() -> {
                assertEquals(
                        model.getData(),
                        stormify().readOne(StressTable.class, "SELECT * FROM " + new StressTable().tableName() + " WHERE id = ?", model.getId()).getData()
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
        assertEquals(1000, s.readOne(int.class, "SELECT COUNT(*) FROM " + new StressTable().tableName()));

        try {
            s.transaction(() -> {
                for (int i = 500; i < 600; i++) {
                    new StressTable(i).delete();
                }
                assertEquals(900, s.readOne(int.class, "SELECT COUNT(*) FROM " + new StressTable().tableName()));
                throw new Exception("Request Rollback");
            });
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
            assertEquals("Request Rollback", ex.getCause().getMessage());
        }

        assertEquals(1000, s.readOne(int.class, "SELECT COUNT(*) FROM " + new StressTable().tableName()));

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

        assertEquals(1000, s.readOne(int.class, "SELECT COUNT(*) FROM " + new StressTable().tableName()));
        assertEquals(1000, countSimple.get(), "Simple queries");
        assertEquals(1000, countSelect.get(), "ORM queries");
        assertEquals(1000, countInserts.get(), "Insert queries");

        logger.get();   // Ignore logging
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


