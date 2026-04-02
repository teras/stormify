package onl.ycode.stormify;

// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

import org.junit.jupiter.api.*;

import java.util.stream.IntStream;

import static onl.ycode.stormify.StormifyManager.stormify;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransactionTest extends BaseDbTest {

    @Test
    @Order(1)
    void testTransactions() {
        StormifyManager s = stormify();

        TestDDL.dropTable("tx_test");
        s.executeUpdate(TestDDL.createTable("tx_test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));
        s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", 2, "Test2");

        // Rollback
        try {
            s.transaction(() -> {
                IntStream.rangeClosed(3, 5).forEach(id ->
                        s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test" + id));
                throw new Exception("Request Rollback");
            });
        } catch (Exception ex) {
            assertEquals("Request Rollback", ex.getCause().getMessage());
        }
        assertEquals("[Test2]", s.read(String.class, "SELECT name FROM tx_test ORDER BY id").toString());

        // Commit
        s.transaction(() -> IntStream.rangeClosed(3, 5).forEach(id ->
                s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test" + id)));
        assertEquals("[Test2, Test3, Test4, Test5]",
                s.read(String.class, "SELECT name FROM tx_test ORDER BY id").toString());

        // Cleanup
        s.transaction(() -> IntStream.rangeClosed(3, 5).forEach(id ->
                s.executeUpdate("DELETE FROM tx_test WHERE id = ?", id)));

        // Nested with rollback
        s.transaction(() -> {
            IntStream.rangeClosed(3, 4).forEach(id ->
                    s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test" + id));
            assertEquals("[Test2, Test3, Test4]",
                    s.read(String.class, "SELECT name FROM tx_test ORDER BY id").toString());

            try {
                s.transaction(() -> {
                    IntStream.rangeClosed(5, 6).forEach(id ->
                            s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test" + id));
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
    }
}
