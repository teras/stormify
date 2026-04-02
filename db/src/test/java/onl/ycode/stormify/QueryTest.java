package onl.ycode.stormify;

// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

import onl.ycode.stormify.pojos.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static onl.ycode.stormify.StormifyManager.stormify;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryTest extends BaseDbTest {

    @Test
    @Order(1)
    void testFindAllVariations() {
        StormifyManager s = stormify();
        TestDDL.dropTable("findall_test");
        s.executeUpdate(TestDDL.createTable("findall_test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));
        s.executeUpdate("INSERT INTO findall_test (id, name) VALUES (?, ?)", 1, "Alice");
        s.executeUpdate("INSERT INTO findall_test (id, name) VALUES (?, ?)", 2, "Bob");
        s.executeUpdate("INSERT INTO findall_test (id, name) VALUES (?, ?)", 3, "Charlie");

        assertEquals(3, s.read(TestC.class, "SELECT * FROM findall_test").size());
        assertEquals(2, s.read(TestC.class, "SELECT * FROM findall_test WHERE id > ?", 1).size());
        assertEquals(0, s.read(TestC.class, "SELECT * FROM findall_test WHERE id > ?", 100).size());

        // readCursor
        final int[] count = {0};
        s.readCursor(TestC.class, "SELECT * FROM findall_test", item -> count[0]++);
        assertEquals(3, count[0]);

        // Scalar reads
        assertEquals(3, (int) s.readOne(int.class, "SELECT COUNT(*) FROM findall_test"));
        List<String> names = s.read(String.class, "SELECT name FROM findall_test ORDER BY name");
        assertEquals("[Alice, Bob, Charlie]", names.toString());
    }

    @Test
    @Order(2)
    void testReadOneNotFound() {
        StormifyManager s = stormify();
        TestDDL.dropTable("read_test");
        s.executeUpdate(TestDDL.createTable("read_test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));

        assertNull(s.readOne(String.class, "SELECT name FROM read_test WHERE id = ?", 999));
        assertNull(s.findById(TestC.class, 999));
    }

    @Test
    @Order(3)
    void testOnTheFlyFields() {
        StormifyManager s = stormify();

        TestDDL.dropTable("fly_test");
        s.executeUpdate(TestDDL.createTable("fly_test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));
        s.executeUpdate("INSERT INTO fly_test (id, name) VALUES (?, ?)", 1, "Alice");
        s.executeUpdate("INSERT INTO fly_test (id, name) VALUES (?, ?)", 2, "Bob");

        // Alias
        FlyView withLabel = s.readOne(FlyView.class, "SELECT id, name, name as label FROM fly_test WHERE id = ?", 1);
        assertEquals("Alice", withLabel.getName());
        assertEquals("Alice", withLabel.getLabel());

        // Fewer columns
        FlyView partial = s.readOne(FlyView.class, "SELECT id, name FROM fly_test WHERE id = ?", 2);
        assertEquals("Bob", partial.getName());
        assertNull(partial.getLabel());

        // Only alias
        FlyView onlyLabel = s.readOne(FlyView.class, "SELECT id, name as label FROM fly_test WHERE id = ?", 1);
        assertNull(onlyLabel.getName());
        assertEquals("Alice", onlyLabel.getLabel());

        // Extra column ignored
        FlyView withBonus = s.readOne(FlyView.class, "SELECT id, name, name as label, id as bonus FROM fly_test WHERE id = ?", 1);
        assertEquals("Alice", withBonus.getLabel());

        // Mixed case aliases
        FlyView mixedCase = s.readOne(FlyView.class, "SELECT id as Id, name as NaMe, name as LaBeL FROM fly_test WHERE id = ?", 1);
        assertEquals("Alice", mixedCase.getName());
        assertEquals("Alice", mixedCase.getLabel());

        // Purely computed
        FlyView computed = s.readOne(FlyView.class, TestDDL.selectExpr("42 as id, 'hello' as name, 'world' as label"));
        assertEquals(42, computed.getId());
        assertEquals("hello", computed.getName());
        assertEquals("world", computed.getLabel());
    }

    @SuppressWarnings("unchecked")
    @Test
    @Order(4)
    void testMapRead() {
        StormifyManager s = stormify();
        TestDDL.dropTable("map_test");
        s.executeUpdate(TestDDL.createTable("map_test",
                TestDDL.intPrimaryKey("id") + ", name " + TestDDL.textType()));
        s.executeUpdate("INSERT INTO map_test (id, name) VALUES (?, ?)", 1, "Alice");
        s.executeUpdate("INSERT INTO map_test (id, name) VALUES (?, ?)", 2, "Bob");

        // Single row
        Map<String, Object> row = (Map<String, Object>) s.readOne(Map.class, "SELECT id, name FROM map_test WHERE id = ?", 1);
        assertNotNull(row);
        assertEquals("Alice", row.get("name"));

        // Multiple rows
        List<Map> rows = s.read(Map.class, "SELECT id, name FROM map_test ORDER BY id");
        assertEquals(2, rows.size());
        assertEquals("Bob", ((Map<String, Object>) rows.get(1)).get("name"));

        // Alias
        Map<String, Object> aliased = (Map<String, Object>) s.readOne(Map.class, "SELECT id, name as label FROM map_test WHERE id = ?", 1);
        assertEquals("Alice", aliased.get("label"));
        assertNull(aliased.get("name"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @Order(5)
    void testAggregatesViaMap() {
        StormifyManager s = stormify();
        // Reuse map_test

        Map<String, Object> agg = (Map<String, Object>) s.readOne(Map.class,
                "SELECT COUNT(*) as cnt, MIN(id) as min_id, MAX(id) as max_id FROM map_test");
        assertNotNull(agg);
        assertEquals(2, ((Number) agg.get("cnt")).intValue());
        assertEquals(1, ((Number) agg.get("min_id")).intValue());
        assertEquals(2, ((Number) agg.get("max_id")).intValue());
    }

    @SuppressWarnings("unchecked")
    @Test
    @Order(6)
    void testJoinsViaMap() {
        StormifyManager s = stormify();
        TestDDL.dropTable("order_items");
        TestDDL.dropTable("orders");
        s.executeUpdate(TestDDL.createTable("orders",
                TestDDL.intPrimaryKey("id") + ", customer " + TestDDL.textType()));
        s.executeUpdate(TestDDL.createTable("order_items",
                TestDDL.intPrimaryKey("id") + ", order_id INT, product " + TestDDL.textType()));

        s.executeUpdate("INSERT INTO orders (id, customer) VALUES (?, ?)", 1, "Alice");
        s.executeUpdate("INSERT INTO order_items (id, order_id, product) VALUES (?, ?, ?)", 1, 1, "Widget");
        s.executeUpdate("INSERT INTO order_items (id, order_id, product) VALUES (?, ?, ?)", 2, 1, "Gadget");

        List<Map> rows = s.read(Map.class,
                "SELECT o.customer, i.product FROM orders o JOIN order_items i ON o.id = i.order_id ORDER BY i.product");
        assertEquals(2, rows.size());
        assertEquals("Gadget", ((Map<String, Object>) rows.get(0)).get("product"));
        assertEquals("Alice", ((Map<String, Object>) rows.get(0)).get("customer"));
    }
}
